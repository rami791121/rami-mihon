# -*- coding: utf-8 -*-
"""Mihon extension-store protobuf definitions.

Schema source:
https://github.com/mihonapp/mihon/blob/main/data/src/main/java/mihon/data/extension/model/NetworkExtensionStore.kt
"""

from google.protobuf import descriptor_pb2 as _descriptor_pb2
from google.protobuf import descriptor_pool as _descriptor_pool
from google.protobuf import symbol_database as _symbol_database
from google.protobuf.internal import builder as _builder


_sym_db = _symbol_database.Default()
_file = _descriptor_pb2.FileDescriptorProto(name="index.proto", syntax="proto3")


def _message(name):
    message = _file.message_type.add()
    message.name = name
    return message


def _field(message, name, number, field_type, *, repeated=False, type_name=None):
    field = message.field.add()
    field.name = name
    field.number = number
    field.type = field_type
    field.label = (
        _descriptor_pb2.FieldDescriptorProto.LABEL_REPEATED
        if repeated
        else _descriptor_pb2.FieldDescriptorProto.LABEL_OPTIONAL
    )
    if type_name is not None:
        field.type_name = type_name


_string = _descriptor_pb2.FieldDescriptorProto.TYPE_STRING
_int64 = _descriptor_pb2.FieldDescriptorProto.TYPE_INT64
_message_type = _descriptor_pb2.FieldDescriptorProto.TYPE_MESSAGE
_enum_type = _descriptor_pb2.FieldDescriptorProto.TYPE_ENUM

_content_warning = _file.enum_type.add()
_content_warning.name = "ContentWarning"
for _name, _number in (
    ("CONTENT_WARNING_UNSPECIFIED", 0),
    ("CONTENT_WARNING_SAFE", 1),
    ("CONTENT_WARNING_MIXED", 2),
    ("CONTENT_WARNING_NSFW", 3),
):
    _value = _content_warning.value.add()
    _value.name = _name
    _value.number = _number

_index = _message("Index")
_field(_index, "name", 1, _string)
_field(_index, "badgeLabel", 2, _string)
_field(_index, "signingKey", 3, _string)
_field(_index, "contact", 4, _message_type, type_name=".Contact")
_field(_index, "extensionList", 101, _message_type, type_name=".ExtensionList")
_field(_index, "extensionListUrl", 102, _string)

_contact = _message("Contact")
_field(_contact, "website", 1, _string)
_field(_contact, "discord", 2, _string)

_extension_list = _message("ExtensionList")
_field(
    _extension_list,
    "extensions",
    1,
    _message_type,
    repeated=True,
    type_name=".Extension",
)

_extension = _message("Extension")
_field(_extension, "name", 1, _string)
_field(_extension, "packageName", 2, _string)
_field(_extension, "resources", 3, _message_type, type_name=".Resources")
_field(_extension, "extensionLib", 4, _string)
_field(_extension, "versionCode", 5, _int64)
_field(_extension, "versionName", 6, _string)
_field(_extension, "contentWarning", 7, _enum_type, type_name=".ContentWarning")
_field(_extension, "sources", 8, _message_type, repeated=True, type_name=".Source")

_resources = _message("Resources")
_field(_resources, "apkUrl", 1, _string)
_field(_resources, "iconUrl", 2, _string)

_source = _message("Source")
_field(_source, "id", 1, _int64)
_field(_source, "name", 2, _string)
_field(_source, "language", 3, _string)
_field(_source, "homeUrl", 4, _string)
_field(_source, "mirrorUrls", 5, _string, repeated=True)
_field(_source, "message", 7, _string)

DESCRIPTOR = _descriptor_pool.Default().AddSerializedFile(_file.SerializeToString())
_globals = globals()
_builder.BuildMessageAndEnumDescriptors(DESCRIPTOR, _globals)
_builder.BuildTopDescriptorsAndMessages(DESCRIPTOR, "index_pb2", _globals)
