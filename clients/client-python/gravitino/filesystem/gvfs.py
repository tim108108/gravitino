"""
Copyright 2024 Datastrato Pvt Ltd.
This software is licensed under the Apache License version 2.
"""

# pylint: disable=too-many-instance-attributes

import os
import socket
import subprocess
from enum import Enum
from pathlib import PurePosixPath
from typing import Dict, Tuple, List
import re
import fsspec

from cachetools import TTLCache, LRUCache
from fsspec import AbstractFileSystem
from fsspec.implementations.local import LocalFileSystem
from fsspec.implementations.arrow import ArrowFSWrapper
from fsspec.utils import infer_storage_options
from pyarrow.fs import HadoopFileSystem
from readerwriterlock import rwlock

from gravitino.api.base_fileset_data_operation_ctx import BaseFilesetDataOperationCtx
from gravitino.api.client_type import ClientType
from gravitino.api.fileset_context import FilesetContext
from gravitino.api.fileset_data_operation import FilesetDataOperation
from gravitino.api.source_engine_type import SourceEngineType
from gravitino.auth.simple_auth_provider import SimpleAuthProvider
from gravitino.auth.token_auth_provider import TokenAuthProvider
from gravitino.catalog.fileset_catalog import FilesetCatalog
from gravitino.client.gravitino_client import GravitinoClient
from gravitino.exceptions.gravitino_runtime_exception import GravitinoRuntimeException
from gravitino.filesystem.gvfs_config import GVFSConfig
from gravitino.name_identifier import NameIdentifier

PROTOCOL_NAME = "gvfs"


class StorageType(Enum):
    HDFS = "hdfs"
    LOCAL = "file"
    LAVAFS = "lavafs"


class FilesetContextPair:
    """A context pair object that holds the information about the fileset context and actual paths."""

    def __init__(
        self, fileset_context: FilesetContext, filesystems: List[AbstractFileSystem]
    ):
        self._fileset_context = fileset_context
        self._filesystems = filesystems

    def fileset_context(self):
        return self._fileset_context

    def filesystems(self):
        return self._filesystems


class GravitinoVirtualFileSystem(fsspec.AbstractFileSystem):
    """This is a virtual file system which users can access `fileset` and
    other resources.

    It obtains the actual storage location corresponding to the resource from the
    Gravitino server, and creates an independent file system for it to act as an agent for users to
    access the underlying storage.
    """

    # Override the parent variable
    protocol = PROTOCOL_NAME
    _identifier_pattern = re.compile("^fileset/([^/]+)/([^/]+)/([^/]+)(?:/[^/]+)*/?$")
    _hadoop_classpath = None
    _is_cloudml_env = os.environ.get("CLOUDML_JOB_ID") is not None

    def __init__(
        self,
        server_uri: str = None,
        metalake_name: str = None,
        options: Dict = None,
        **kwargs,
    ):
        if metalake_name is not None:
            self._metalake = metalake_name
        else:
            metalake = os.environ.get("GRAVITINO_METALAKE")
            if metalake is None:
                raise GravitinoRuntimeException(
                    "No metalake name is provided. Please set the environment variable "
                    + "'GRAVITINO_METALAKE' or provide it as a parameter."
                )
            self._metalake = metalake
        if server_uri is not None:
            self._server_uri = server_uri
        else:
            server_uri = os.environ.get("GRAVITINO_SERVER")
            if server_uri is None:
                raise GravitinoRuntimeException(
                    "No server URI is provided. Please set the environment variable "
                    + "'GRAVITINO_SERVER' or provide it as a parameter."
                )
            self._server_uri = server_uri
        auth_type = (
            GVFSConfig.DEFAULT_AUTH_TYPE
            if options is None
            else options.get(GVFSConfig.AUTH_TYPE, GVFSConfig.DEFAULT_AUTH_TYPE)
        )
        if auth_type == GVFSConfig.DEFAULT_AUTH_TYPE:
            self._client = GravitinoClient(
                uri=self._server_uri,
                metalake_name=self._metalake,
                auth_data_provider=SimpleAuthProvider(),
            )
        elif auth_type == GVFSConfig.TOKEN_AUTH_TYPE:
            token_value = options.get(GVFSConfig.TOKEN_VALUE, None)
            assert (
                token_value is not None
            ), "Token value is not provided when using token auth type."
            token_provider: TokenAuthProvider = TokenAuthProvider(token_value)
            self._client = GravitinoClient(
                uri=self._server_uri,
                metalake_name=self._metalake,
                auth_data_provider=token_provider,
            )
        else:
            raise GravitinoRuntimeException(
                f"Authentication type {auth_type} is not supported."
            )
        cache_size = (
            GVFSConfig.DEFAULT_CACHE_SIZE
            if options is None
            else options.get(GVFSConfig.CACHE_SIZE, GVFSConfig.DEFAULT_CACHE_SIZE)
        )
        cache_expired_time = (
            GVFSConfig.DEFAULT_CACHE_EXPIRED_TIME
            if options is None
            else options.get(
                GVFSConfig.CACHE_EXPIRED_TIME, GVFSConfig.DEFAULT_CACHE_EXPIRED_TIME
            )
        )
        assert cache_expired_time != 0, "Cache expired time cannot be 0."
        assert cache_size > 0, "Cache size cannot be less than or equal to 0."
        if cache_expired_time < 0:
            self._cache = LRUCache(maxsize=cache_size)
        else:
            self._cache = TTLCache(maxsize=cache_size, ttl=cache_expired_time)
        self._cache_lock = rwlock.RWLockFair()

        self._catalog_cache = LRUCache(maxsize=100)
        self._catalog_cache_lock = rwlock.RWLockFair()

        self._source_engine_type = self._get_source_engine_type()
        self._local_address = self._get_local_address()
        self._app_id = self._get_app_id()
        self._client_version = self._client.get_client_version()
        self._extra_info = self._get_extra_info()

        super().__init__(**kwargs)

    @property
    def fsid(self):
        return PROTOCOL_NAME

    def sign(self, path, expiration=None, **kwargs):
        """We do not support to create a signed URL representing the given path in gvfs."""
        raise GravitinoRuntimeException(
            "Sign is not implemented for Gravitino Virtual FileSystem."
        )

    def ls(self, path, detail=True, **kwargs):
        """List the files and directories info of the path.
        :param path: Virtual fileset path
        :param detail: Whether to show the details for the files and directories info
        :param kwargs: Extra args
        :return If details is true, returns a list of file info dicts, else returns a list of file paths
        """
        context_pair: FilesetContextPair = self._get_fileset_context(
            path, FilesetDataOperation.LIST_STATUS
        )
        actual_path = context_pair.fileset_context().actual_paths()[0]
        storage_type = self._recognize_storage_type(actual_path)
        pre_process_path: str = self._pre_process_path(path)
        identifier: NameIdentifier = self._extract_identifier(pre_process_path)

        if detail:
            entries = [
                self._convert_actual_info(entry, context_pair, storage_type, identifier)
                for entry in context_pair.filesystems()[0].ls(
                    self._strip_storage_protocol(storage_type, actual_path),
                    detail=True,
                )
            ]
            return entries
        entries = [
            self._convert_actual_path(
                entry_path, context_pair, storage_type, identifier
            )
            for entry_path in context_pair.filesystems()[0].ls(
                self._strip_storage_protocol(storage_type, actual_path),
                detail=False,
            )
        ]
        return entries

    def info(self, path, **kwargs):
        """Get file info.
        :param path: Virtual fileset path
        :param kwargs: Extra args
        :return A file info dict
        """
        context_pair: FilesetContextPair = self._get_fileset_context(
            path, FilesetDataOperation.GET_FILE_STATUS
        )
        actual_path = context_pair.fileset_context().actual_paths()[0]
        storage_type = self._recognize_storage_type(actual_path)
        pre_process_path: str = self._pre_process_path(path)
        identifier: NameIdentifier = self._extract_identifier(pre_process_path)
        actual_info: Dict = context_pair.filesystems()[0].info(
            self._strip_storage_protocol(storage_type, actual_path)
        )
        return self._convert_actual_info(
            actual_info, context_pair, storage_type, identifier
        )

    def exists(self, path, **kwargs):
        """Check if a file or a directory exists.
        :param path: Virtual fileset path
        :param kwargs: Extra args
        :return If a file or directory exists, it returns True, otherwise False
        """
        context_pair: FilesetContextPair = self._get_fileset_context(
            path, FilesetDataOperation.EXISTS
        )
        actual_path = context_pair.fileset_context().actual_paths()[0]
        storage_type = self._recognize_storage_type(actual_path)
        return context_pair.filesystems()[0].exists(
            self._strip_storage_protocol(storage_type, actual_path)
        )

    def cp_file(self, path1, path2, **kwargs):
        """Copy a file.
        :param path1: Virtual src fileset path
        :param path2: Virtual dst fileset path, should be consistent with the src path fileset identifier
        :param kwargs: Extra args
        """
        src_path = self._pre_process_path(path1)
        dst_path = self._pre_process_path(path2)
        src_identifier: NameIdentifier = self._extract_identifier(src_path)
        dst_identifier: NameIdentifier = self._extract_identifier(dst_path)
        if src_identifier != dst_identifier:
            raise GravitinoRuntimeException(
                f"Destination file path identifier: `{dst_identifier}` should be same with src file path "
                f"identifier: `{src_identifier}`."
            )
        src_context_pair: FilesetContextPair = self._get_fileset_context(
            src_path, FilesetDataOperation.COPY_FILE
        )
        src_actual_path = src_context_pair.fileset_context().actual_paths()[0]
        storage_type = self._recognize_storage_type(src_actual_path)
        dst_context_pair: FilesetContextPair = self._get_fileset_context(
            dst_path, FilesetDataOperation.COPY_FILE
        )
        dst_actual_path = dst_context_pair.fileset_context().actual_paths()[0]

        src_context_pair.filesystems()[0].cp_file(
            self._strip_storage_protocol(storage_type, src_actual_path),
            self._strip_storage_protocol(storage_type, dst_actual_path),
        )

    def mv(self, path1, path2, recursive=False, maxdepth=None, **kwargs):
        """Move a file to another directory.
         This can move a file to another existing directory.
         If the target path directory does not exist, an exception will be thrown.
        :param path1: Virtual src fileset path
        :param path2: Virtual dst fileset path, should be consistent with the src path fileset identifier
        :param recursive: Whether to move recursively
        :param maxdepth: Maximum depth of recursive move
        :param kwargs: Extra args
        """
        src_path = self._pre_process_path(path1)
        dst_path = self._pre_process_path(path2)
        src_identifier: NameIdentifier = self._extract_identifier(src_path)
        dst_identifier: NameIdentifier = self._extract_identifier(dst_path)
        if src_identifier != dst_identifier:
            raise GravitinoRuntimeException(
                f"Destination file path identifier: `{dst_identifier}`"
                f" should be same with src file path identifier: `{src_identifier}`."
            )

        src_context_pair: FilesetContextPair = self._get_fileset_context(
            src_path, FilesetDataOperation.RENAME
        )
        src_actual_path = src_context_pair.fileset_context().actual_paths()[0]
        storage_type = self._recognize_storage_type(src_actual_path)
        dst_context_pair: FilesetContextPair = self._get_fileset_context(
            dst_path, FilesetDataOperation.RENAME
        )
        dst_actual_path = dst_context_pair.fileset_context().actual_paths()[0]

        if storage_type in (StorageType.HDFS, StorageType.LAVAFS):
            src_context_pair.filesystems()[0].mv(
                self._strip_storage_protocol(storage_type, src_actual_path),
                self._strip_storage_protocol(storage_type, dst_actual_path),
            )
        elif storage_type == StorageType.LOCAL:
            src_context_pair.filesystems()[0].mv(
                self._strip_storage_protocol(storage_type, src_actual_path),
                self._strip_storage_protocol(storage_type, dst_actual_path),
                recursive,
                maxdepth,
            )
        else:
            raise GravitinoRuntimeException(
                f"Storage type:{storage_type} doesn't support now."
            )

    def _rm(self, path):
        raise GravitinoRuntimeException(
            "Deprecated method, use `rm_file` method instead."
        )

    def rm(self, path, recursive=False, maxdepth=None):
        """Remove a file or directory.
        :param path: Virtual fileset path
        :param recursive: Whether to remove the directory recursively.
                When removing a directory, this parameter should be True.
        :param maxdepth: The maximum depth to remove the directory recursively.
        """
        context_pair: FilesetContextPair = self._get_fileset_context(
            path, FilesetDataOperation.DELETE
        )
        actual_path = context_pair.fileset_context().actual_paths()[0]
        storage_type = self._recognize_storage_type(actual_path)
        context_pair.filesystems()[0].rm(
            self._strip_storage_protocol(storage_type, actual_path),
            recursive,
            maxdepth,
        )

    def rm_file(self, path):
        """Remove a file.
        :param path: Virtual fileset path
        """
        context_pair: FilesetContextPair = self._get_fileset_context(
            path, FilesetDataOperation.DELETE
        )
        actual_path = context_pair.fileset_context().actual_paths()[0]
        storage_type = self._recognize_storage_type(actual_path)
        context_pair.filesystems()[0].rm_file(
            self._strip_storage_protocol(storage_type, actual_path)
        )

    def rmdir(self, path):
        """Remove a directory.
        It will delete a directory and all its contents recursively for PyArrow.HadoopFileSystem.
        And it will throw an exception if delete a directory which is non-empty for LocalFileSystem.
        :param path: Virtual fileset path
        """
        context_pair: FilesetContextPair = self._get_fileset_context(
            path, FilesetDataOperation.DELETE
        )
        actual_path = context_pair.fileset_context().actual_paths()[0]
        storage_type = self._recognize_storage_type(actual_path)
        context_pair.filesystems()[0].rmdir(
            self._strip_storage_protocol(storage_type, actual_path)
        )

    def open(
        self,
        path,
        mode="rb",
        block_size=None,
        cache_options=None,
        compression=None,
        **kwargs,
    ):
        """Open a file to read/write/append.
        :param path: Virtual fileset path
        :param mode: The mode now supports: rb(read), wb(write), ab(append). See builtin ``open()``
        :param block_size: Some indication of buffering - this is a value in bytes
        :param cache_options: Extra arguments to pass through to the cache
        :param compression: If given, open file using compression codec
        :param kwargs: Extra args
        :return A file-like object from the filesystem
        """
        if mode.startswith("w"):
            data_operation = FilesetDataOperation.CREATE
        elif mode.startswith("a"):
            data_operation = FilesetDataOperation.APPEND
        else:
            data_operation = FilesetDataOperation.OPEN
        context_pair: FilesetContextPair = self._get_fileset_context(
            path, data_operation
        )
        actual_path = context_pair.fileset_context().actual_paths()[0]
        storage_type = self._recognize_storage_type(actual_path)
        return context_pair.filesystems()[0].open(
            self._strip_storage_protocol(storage_type, actual_path),
            mode,
            block_size,
            cache_options,
            compression,
            **kwargs,
        )

    def mkdir(self, path, create_parents=True, **kwargs):
        """Make a directory.
        if create_parents=True, this is equivalent to ``makedirs``.

        :param path: Virtual fileset path
        :param create_parents: Create parent directories if missing when set to True
        :param kwargs: Extra args
        """
        context_pair: FilesetContextPair = self._get_fileset_context(
            path, FilesetDataOperation.MKDIRS
        )
        actual_path = context_pair.fileset_context().actual_paths()[0]
        storage_type = self._recognize_storage_type(actual_path)
        context_pair.filesystems()[0].mkdir(
            self._strip_storage_protocol(storage_type, actual_path),
            create_parents,
            **kwargs,
        )

    def makedirs(self, path, exist_ok=True):
        """Make a directory recursively.
        :param path: Virtual fileset path
        :param exist_ok: Continue if a directory already exists
        """
        context_pair: FilesetContextPair = self._get_fileset_context(
            path, FilesetDataOperation.MKDIRS
        )
        actual_path = context_pair.fileset_context().actual_paths()[0]
        storage_type = self._recognize_storage_type(actual_path)
        context_pair.filesystems()[0].makedirs(
            self._strip_storage_protocol(storage_type, actual_path),
            exist_ok,
        )

    def created(self, path):
        """Return the created timestamp of a file as a datetime.datetime
        Only supports for `fsspec.LocalFileSystem` now.
        :param path: Virtual fileset path
        :return Created time(datetime.datetime)
        """
        context_pair: FilesetContextPair = self._get_fileset_context(
            path, FilesetDataOperation.CREATED_TIME
        )
        actual_path = context_pair.fileset_context().actual_paths()[0]
        storage_type = self._recognize_storage_type(actual_path)
        if storage_type == StorageType.LOCAL:
            return context_pair.filesystems()[0].created(
                self._strip_storage_protocol(storage_type, actual_path)
            )
        raise GravitinoRuntimeException(
            f"Storage type:{storage_type} doesn't support now."
        )

    def modified(self, path):
        """Returns the modified time of the path file if it exists.
        :param path: Virtual fileset path
        :return Modified time(datetime.datetime)
        """
        context_pair: FilesetContextPair = self._get_fileset_context(
            path, FilesetDataOperation.MODIFIED_TIME
        )
        actual_path = context_pair.fileset_context().actual_paths()[0]
        storage_type = self._recognize_storage_type(actual_path)
        return context_pair.filesystems()[0].modified(
            self._strip_storage_protocol(storage_type, actual_path)
        )

    def cat_file(self, path, start=None, end=None, **kwargs):
        """Get the content of a file.
        :param path: Virtual fileset path
        :param start: The offset in bytes to start reading from. It can be None.
        :param end: The offset in bytes to end reading at. It can be None.
        :param kwargs: Extra args
        :return File content
        """
        context_pair: FilesetContextPair = self._get_fileset_context(
            path, FilesetDataOperation.CAT_FILE
        )
        actual_path = context_pair.fileset_context().actual_paths()[0]
        storage_type = self._recognize_storage_type(actual_path)
        return context_pair.filesystems()[0].cat_file(
            self._strip_storage_protocol(storage_type, actual_path),
            start,
            end,
            **kwargs,
        )

    def get_file(self, rpath, lpath, callback=None, outfile=None, **kwargs):
        """Copy single remote file to local.
        :param rpath: Remote file path
        :param lpath: Local file path
        :param callback: The callback class
        :param outfile: The output file path
        :param kwargs: Extra args
        """
        if not lpath.startswith(f"{StorageType.LOCAL.value}:") and not lpath.startswith(
            "/"
        ):
            raise GravitinoRuntimeException(
                "Doesn't support copy a remote gvfs file to an another remote file."
            )
        context_pair: FilesetContextPair = self._get_fileset_context(
            rpath, FilesetDataOperation.GET_FILE
        )
        actual_path = context_pair.fileset_context().actual_paths()[0]
        storage_type = self._recognize_storage_type(actual_path)
        context_pair.filesystems()[0].get_file(
            self._strip_storage_protocol(storage_type, actual_path),
            lpath,
            **kwargs,
        )

    def _convert_actual_path(
        self,
        path: str,
        context_pair: FilesetContextPair,
        storage_type: StorageType,
        ident: NameIdentifier,
    ):
        """Convert an actual path to a virtual path.
          The virtual path is like `fileset/{catalog}/{schema}/{fileset}/xxx`.
        :param path: Actual path
        :param context_pair: Fileset context pair
        :param storage_type: Storage type
        :param ident: Fileset name identifier
        :return A virtual path
        """
        if storage_type in (StorageType.HDFS, StorageType.LAVAFS):
            actual_prefix = infer_storage_options(
                context_pair.fileset_context().fileset().storage_location()
            )["path"]
        elif storage_type == StorageType.LOCAL:
            actual_prefix = (
                context_pair.fileset_context()
                .fileset()
                .storage_location()[len(f"{StorageType.LOCAL.value}:") :]
            )
        else:
            raise GravitinoRuntimeException(
                f"Storage type:{storage_type} doesn't support now."
            )

        if not path.startswith(actual_prefix):
            raise GravitinoRuntimeException(
                f"Path {path} does not start with valid prefix {actual_prefix}."
            )
        virtual_location = self._get_virtual_location(ident)
        return f"{path.replace(actual_prefix, virtual_location)}"

    def _convert_actual_info(
        self,
        entry: Dict,
        context_pair: FilesetContextPair,
        storage_type: StorageType,
        ident: NameIdentifier,
    ):
        """Convert a file info from an actual entry to a virtual entry.
        :param entry: A dict of the actual file info
        :param context_pair: Fileset context pair
        :param storage_type: Storage type
        :param ident: Fileset name identifier
        :return A dict of the virtual file info
        """
        path = self._convert_actual_path(
            entry["name"], context_pair, storage_type, ident
        )
        return {
            "name": path,
            "size": entry["size"],
            "type": entry["type"],
            "mtime": entry["mtime"],
        }

    def _get_fileset_context(self, virtual_path: str, operation: FilesetDataOperation):
        """Get a fileset context from the cache or the Gravitino server
        :param virtual_path: The virtual path
        :param operation: The data operation
        :return A fileset context pair
        """
        virtual_path: str = self._pre_process_path(virtual_path)
        identifier: NameIdentifier = self._extract_identifier(virtual_path)
        catalog_ident: NameIdentifier = NameIdentifier.of_catalog(
            self._metalake, identifier.namespace().level(1)
        )
        fileset_catalog = self._get_fileset_catalog(catalog_ident)
        assert (
            fileset_catalog is not None
        ), f"Loaded fileset catalog: {catalog_ident} is null."

        sub_path: str = virtual_path[
            len(
                f"fileset/{identifier.namespace().level(1)}/{identifier.namespace().level(2)}/{identifier.name()}"
            ) :
        ]
        ctx = BaseFilesetDataOperationCtx(
            sub_path=sub_path,
            operation=operation,
            client_type=ClientType.PYTHON_GVFS,
            source_engine_type=self._source_engine_type,
            ip=self._local_address,
            app_id=self._app_id,
            extra_info=self._extra_info,
        )
        context: FilesetContext = (
            fileset_catalog.as_fileset_catalog().get_fileset_context(identifier, ctx)
        )

        filesystems: List[AbstractFileSystem] = [
            self._get_filesystem(actual_path) for actual_path in context.actual_paths()
        ]
        return FilesetContextPair(context, filesystems)

    def _extract_identifier(self, path):
        """Extract the fileset identifier from the path.
        :param path: The virtual fileset path
        :return The fileset identifier
        """
        if path is None:
            raise GravitinoRuntimeException(
                "path which need be extracted cannot be null or empty."
            )

        match = self._identifier_pattern.match(path)
        if match and len(match.groups()) == 3:
            return NameIdentifier.of_fileset(
                self._metalake, match.group(1), match.group(2), match.group(3)
            )
        raise GravitinoRuntimeException(
            f"path: `{path}` doesn't contains valid identifier."
        )

    @staticmethod
    def _get_virtual_location(identifier: NameIdentifier):
        """Get the virtual location of the fileset.
        :param identifier: The name identifier of the fileset
        :return The virtual location.
        """
        return (
            f"fileset/{identifier.namespace().level(1)}"
            f"/{identifier.namespace().level(2)}"
            f"/{identifier.name()}"
        )

    @staticmethod
    def _pre_process_path(virtual_path):
        """Pre-process the path.
         We will uniformly process `gvfs://fileset/{catalog}/{schema}/{fileset_name}/xxx`
         into the format of `fileset/{catalog}/{schema}/{fileset_name}/xxx`.
         This is because some implementations of `PyArrow` and `fsspec` can only recognize this format.
        :param virtual_path: The virtual path
        :return The pre-processed path
        """
        if isinstance(virtual_path, PurePosixPath):
            pre_processed_path = virtual_path.as_posix()
        else:
            pre_processed_path = virtual_path
        gvfs_prefix = f"{PROTOCOL_NAME}://"
        if pre_processed_path.startswith(gvfs_prefix):
            pre_processed_path = pre_processed_path[len(gvfs_prefix) :]
        if not pre_processed_path.startswith("fileset/"):
            raise GravitinoRuntimeException(
                f"Invalid path:`{pre_processed_path}`. Expected path to start with `fileset/`."
                " Example: fileset/{fileset_catalog}/{schema}/{fileset_name}/{sub_path}."
            )
        return pre_processed_path

    @staticmethod
    def _recognize_storage_type(path: str):
        """Recognize the storage type by the path.
        :param path: The path
        :return: The storage type
        """
        if path.startswith(f"{StorageType.HDFS.value}://"):
            return StorageType.HDFS
        if path.startswith(f"{StorageType.LAVAFS.value}://"):
            return StorageType.LAVAFS
        if path.startswith(f"{StorageType.LOCAL.value}:/"):
            return StorageType.LOCAL
        raise GravitinoRuntimeException(
            f"Storage type doesn't support now. Path:{path}"
        )

    @staticmethod
    def _parse_storage_host_path(path: str):
        if path.startswith(f"{StorageType.LOCAL.value}:/"):
            return f"{StorageType.LOCAL}:/"
        if path.startswith(f"{StorageType.HDFS.value}://"):
            match = re.match(r"hdfs://([^/]+)", path)
            if not match:
                raise GravitinoRuntimeException(f"Invalid HDFS path: {path}")
            return match.group(1)
        if path.startswith(f"{StorageType.LAVAFS.value}://"):
            match = re.match(r"lavafs://([^/]+)", path)
            if not match:
                raise GravitinoRuntimeException(f"Invalid LAVAFS path: {path}")
            return match.group(1)
        raise GravitinoRuntimeException(f"Unsupported storage path: {path}")

    @staticmethod
    def _strip_storage_protocol(storage_type: StorageType, path: str):
        """Strip the storage protocol from the path.
          Before passing the path to the underlying file system for processing,
           pre-process the protocol information in the path.
          Some file systems require special processing.
          For HDFS/LAVAFS, we can pass the path like 'hdfs://{host}:{port}/xxx', 'lavafs://{host}:{port}/xxx'.
          For Local, we can pass the path like '/tmp/xxx'.
        :param storage_type: The storage type
        :param path: The path
        :return: The stripped path
        """
        if storage_type == StorageType.HDFS:
            return path
        if storage_type == StorageType.LAVAFS:
            return path
        if storage_type == StorageType.LOCAL:
            return path[len(f"{StorageType.LOCAL.value}:") :]
        raise GravitinoRuntimeException(
            f"Storage type:{storage_type} doesn't support now."
        )

    @staticmethod
    def _get_local_address():
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            return ip
        except Exception as e:
            print(f"Cannot get the local IP address: {e}")

        return "unknown"

    @staticmethod
    def _get_source_engine_type():
        notebook_task = os.environ.get("NOTEBOOK_TASK")
        if notebook_task is not None and notebook_task == "true":
            return SourceEngineType.NOTEBOOK
        spark_dist_classpath = os.environ.get("SPARK_DIST_CLASSPATH")
        if spark_dist_classpath is not None:
            return SourceEngineType.PYSPARK
        if GravitinoVirtualFileSystem._is_cloudml_env is True:
            return SourceEngineType.CLOUDML
        return SourceEngineType.UNKNOWN

    @staticmethod
    def _get_app_id():
        app_id = os.environ.get("APP_ID")
        if app_id is not None:
            return app_id

        cloudml_job_id = os.environ.get("CLOUDML_JOB_ID")
        if cloudml_job_id is not None:
            return cloudml_job_id
        return "unknown"

    def _get_extra_info(self):
        extra_info = {}
        if GravitinoVirtualFileSystem._is_cloudml_env is True:
            cloudml_owner_name = os.environ.get("CLOUDML_OWNER_NAME")
            if cloudml_owner_name is not None:
                extra_info["CLOUDML_OWNER_NAME"] = cloudml_owner_name

            cloudml_job_name = os.environ.get("CLOUDML_EXP_JOBNAME")
            if cloudml_job_name is not None:
                extra_info["CLOUDML_JOB_NAME"] = cloudml_job_name

            cloudml_user = os.environ.get("CLOUDML_USER")
            if cloudml_user is not None:
                extra_info["CLOUDML_USER"] = cloudml_user

            cloudml_dev_image = os.environ.get("XIAOMI_DEV_IMAGE")
            if cloudml_dev_image is not None:
                extra_info["CLOUDML_XIAOMI_DEV_IMAGE"] = cloudml_dev_image

            cloudml_krb_account = os.environ.get("XIAOMI_HDFS_KRB_ACCOUNT")
            if cloudml_krb_account is not None:
                extra_info["CLOUDML_KRB_ACCOUNT"] = cloudml_krb_account

            cloudml_xiaomi_build_image = os.environ.get("XIAOMI_BUILD_IMAGE")
            if cloudml_xiaomi_build_image is not None:
                extra_info["CLOUDML_XIAOMI_BUILD_IMAGE"] = cloudml_xiaomi_build_image

            cluster_name = os.environ.get("CLUSTER_NAME")
            if cluster_name is not None:
                extra_info["CLOUDML_CLUSTER_NAME"] = cluster_name
        extra_info["CLIENT_VERSION"] = self._client_version.version()
        extra_info["CLIENT_COMPILE_DATE"] = self._client_version.compile_date()
        extra_info["CLIENT_GIT_COMMIT"] = self._client_version.git_commit()
        return extra_info

    def _init_hadoop_classpath(self):
        hadoop_home = os.environ.get("HADOOP_HOME")
        hadoop_conf_dir = os.environ.get("HADOOP_CONF_DIR")
        if (
            hadoop_home is not None
            and len(hadoop_home) > 0
            and hadoop_conf_dir is not None
            and len(hadoop_conf_dir) > 0
        ):
            hadoop_shell = f"{hadoop_home}/bin/hadoop"
            if not os.path.exists(hadoop_shell):
                raise GravitinoRuntimeException(
                    f"Hadoop shell:{hadoop_shell} doesn't exist."
                )
            try:
                result = subprocess.run(
                    [hadoop_shell, "classpath", "--glob"],
                    capture_output=True,
                    text=True,
                    # we set check=True to raise exception if the command failed.
                    check=True,
                )
                classpath_str = str(result.stdout)
                origin_classpath = os.environ.get("CLASSPATH")
                # compatible with lavafs in Notebook and PySpark in the cluster
                spark_classpath = os.environ.get("SPARK_DIST_CLASSPATH")
                potential_lavafs_jar_files = []
                current_dir = os.getcwd()
                if spark_classpath is not None:
                    file_list = os.listdir(current_dir)
                    pattern = re.compile(r"^lavafs.*\.jar$")
                    potential_lavafs_jar_files = [
                        file
                        for file in file_list
                        if pattern.match(file)
                        and os.path.isfile(os.path.join(current_dir, file))
                    ]
                new_classpath = hadoop_conf_dir + ":" + classpath_str
                if (
                    potential_lavafs_jar_files is not None
                    and len(potential_lavafs_jar_files) > 0
                ):
                    for lava_jar in potential_lavafs_jar_files:
                        new_classpath = (
                            new_classpath + ":" + os.path.join(current_dir, lava_jar)
                        )
                if origin_classpath is None or len(origin_classpath) == 0:
                    os.environ["CLASSPATH"] = new_classpath
                else:
                    os.environ["CLASSPATH"] = origin_classpath + ":" + new_classpath
                self._hadoop_classpath = classpath_str
            except subprocess.CalledProcessError as e:
                raise GravitinoRuntimeException(
                    f"Command failed with return code {e.returncode}, stdout:{e.stdout}, stderr:{e.stderr}"
                ) from e
        else:
            raise GravitinoRuntimeException(
                "Failed to get hadoop classpath, please check if hadoop env is configured correctly."
            )

    def _get_fileset_catalog(self, catalog_ident: NameIdentifier):
        read_lock = self._catalog_cache_lock.gen_rlock()
        try:
            read_lock.acquire()
            cache_value: Tuple[NameIdentifier, FilesetCatalog] = (
                self._catalog_cache.get(catalog_ident)
            )
            if cache_value is not None:
                return cache_value
        finally:
            read_lock.release()

        write_lock = self._catalog_cache_lock.gen_wlock()
        try:
            write_lock.acquire()
            cache_value: Tuple[NameIdentifier, FilesetCatalog] = (
                self._catalog_cache.get(catalog_ident)
            )
            if cache_value is not None:
                return cache_value
            catalog = self._client.load_catalog(catalog_ident)
            self._catalog_cache[catalog_ident] = catalog
            return catalog
        finally:
            write_lock.release()

    def _get_filesystem(self, actual_path: str):
        storage_type = self._recognize_storage_type(actual_path)
        storage_host_path = self._parse_storage_host_path(actual_path)
        read_lock = self._cache_lock.gen_rlock()
        try:
            read_lock.acquire()
            cache_value: Tuple[str, AbstractFileSystem] = self._cache.get(
                storage_host_path
            )
            if cache_value is not None:
                return cache_value
        finally:
            read_lock.release()

        write_lock = self._cache_lock.gen_wlock()
        try:
            write_lock.acquire()
            cache_value: Tuple[str, AbstractFileSystem] = self._cache.get(
                storage_host_path
            )
            if cache_value is not None:
                return cache_value
            if storage_type == StorageType.HDFS:
                if self._hadoop_classpath is None:
                    self._init_hadoop_classpath()
                fs = ArrowFSWrapper(HadoopFileSystem.from_uri(actual_path))
            elif storage_type == StorageType.LAVAFS:
                if self._hadoop_classpath is None:
                    self._init_hadoop_classpath()
                fs = ArrowFSWrapper(HadoopFileSystem.from_uri(actual_path))
            elif storage_type == StorageType.LOCAL:
                fs = LocalFileSystem()
            else:
                raise GravitinoRuntimeException(
                    f"Storage path: `{storage_host_path}` doesn't support now."
                )
            self._cache[storage_host_path] = fs
            return fs
        finally:
            write_lock.release()


fsspec.register_implementation(PROTOCOL_NAME, GravitinoVirtualFileSystem)
