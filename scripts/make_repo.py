import html
import json
import re
import shutil
import sys
from pathlib import Path

from google.protobuf import json_format

import index_pb2


staging = Path(sys.argv[1])
output = Path(sys.argv[2])
owner = sys.argv[3]
repo = sys.argv[4]
fingerprint = sys.argv[5]

apk_out = output / "apk"
icon_out = output / "icon"
shutil.rmtree(apk_out, ignore_errors=True)
shutil.rmtree(icon_out, ignore_errors=True)
shutil.copytree(staging / "apk", apk_out)
shutil.copytree(staging / "icon", icon_out)

with (staging / "index.min.json").open(encoding="utf-8") as handle:
    extensions = json.load(handle)

extensions.sort(key=lambda item: item["pkg"])

with (output / "index.min.json").open("w", encoding="utf-8") as handle:
    json.dump(extensions, handle, ensure_ascii=False, separators=(",", ":"))


def extension_lib(version: str) -> str:
    match = re.search(r"(\d+)\.(\d+)", version)
    if not match:
        raise ValueError(f"Unsupported version: {version}")
    return f"{match.group(1)}.{match.group(2)}"


base = f"https://raw.githubusercontent.com/{owner}/{repo}/refs/heads/main"
index = index_pb2.Index(
    name="Rami NTK",
    badgeLabel="RAMI",
    signingKey=fingerprint,
    contact=index_pb2.Contact(website=f"https://github.com/{owner}/{repo}"),
    extensionList=index_pb2.ExtensionList(
        extensions=[
            index_pb2.Extension(
                name=extension["name"].replace("Tachiyomi: ", ""),
                packageName=extension["pkg"],
                resources=index_pb2.Resources(
                    apkUrl=f"{base}/apk/{extension['apk']}",
                    iconUrl=f"{base}/icon/{extension['pkg']}.png",
                ),
                extensionLib=extension_lib(extension["version"]),
                versionCode=extension["code"],
                versionName=extension["version"],
                contentWarning=(
                    index_pb2.CONTENT_WARNING_NSFW
                    if extension["nsfw"] == 1
                    else index_pb2.CONTENT_WARNING_SAFE
                ),
                sources=[
                    index_pb2.Source(
                        id=int(source["id"]),
                        name=source["name"],
                        language=source["lang"],
                        homeUrl=source["baseUrl"],
                    )
                    for source in extension["sources"]
                ],
            )
            for extension in extensions
        ],
    ),
)

with (output / "index.pb").open("wb") as handle:
    handle.write(index.SerializeToString())

with (output / "index.json").open("w", encoding="utf-8") as handle:
    handle.write(
        json_format.MessageToJson(
            index,
            always_print_fields_with_no_presence=False,
            preserving_proto_field_name=True,
        )
    )

with (output / "repo.json").open("w", encoding="utf-8") as handle:
    json.dump(
        {
            "meta": {
                "name": "Rami NTK",
                "website": f"https://github.com/{owner}/{repo}",
                "signingKeyFingerprint": fingerprint,
            }
        },
        handle,
        ensure_ascii=False,
        indent=2,
    )

with (output / "index.html").open("w", encoding="utf-8") as handle:
    handle.write("<!doctype html><meta charset='utf-8'><title>Rami NTK</title><pre>\n")
    for extension in extensions:
        apk = "apk/" + html.escape(extension["apk"])
        name = html.escape(extension["name"])
        handle.write(f'<a href="{apk}">{name}</a>\n')
    handle.write("</pre>\n")
