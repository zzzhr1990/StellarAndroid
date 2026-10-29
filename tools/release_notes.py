"""Write release provenance without including signing material or service credentials."""
import json
import os
from pathlib import Path


def main():
    env = os.environ
    sha = env["GITHUB_SHA"]
    repository = env["GITHUB_REPOSITORY"]
    version = env["APP_VERSION_NAME"]
    code = int(env["APP_VERSION_CODE"])
    tag = env["RELEASE_TAG"]
    dist = Path("dist")
    metadata = {
        "repository": repository,
        "commit": sha,
        "tag": tag,
        "versionName": version,
        "versionCode": code,
        "abi": "arm64-v8a",
        "minSdk": 26,
        "workflowRun": f"https://github.com/{repository}/actions/runs/{env['GITHUB_RUN_ID']}",
        "runAttempt": int(env["GITHUB_RUN_ATTEMPT"]),
    }
    # Verify the packaged APK versions before publishing their provenance.
    for variant in ("debug", "release"):
        apk = json.loads(Path(f"app/build/outputs/apk/{variant}/output-metadata.json").read_text())
        for element in apk["elements"]:
            if element["versionCode"] != code or element["versionName"] != version:
                raise ValueError(f"{variant} APK version does not match the release")
    (dist / "build-info.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (dist / "RELEASE_NOTES.md").write_text(f"""自动构建 / Automated build **{version}** (versionCode: {code}).

- Android 8.0+ (API 26), **arm64-v8a**.
- **release.apk**：使用仓库专用发布密钥签名，可直接安装；后续 CI Release APK 可覆盖升级。
- **release.aab**：使用同一发布密钥签名的 Android App Bundle，供分发使用，不能直接安装。
- **debug.apk**：调试构建，使用本次运行的 Android 调试签名；不能覆盖 Release，跨运行也可能需要先卸载。
- **source.tar.gz**：此提交的仓库源码、原生依赖源码归档及构建脚本；第三方许可与其他源码位置见 THIRD_PARTY_NOTICES.md。
- **SHA256SUMS.txt**：全部发布附件的 SHA-256 校验值（校验文件自身除外）。

Commit: [{sha}](https://github.com/{repository}/commit/{sha})

Build: [{env['GITHUB_RUN_ID']}]({metadata['workflowRun']})

Rebuild: follow README.md and pass `-PappVersionName={version} -PappVersionCode={code}` to Gradle.
Release signing uses private GitHub Secrets; rebuilds must use your own signing key.
Optional TMDB/Trakt credentials are included only when configured as repository secrets.
""", encoding="utf-8")


if __name__ == "__main__":
    main()
