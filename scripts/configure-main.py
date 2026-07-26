#!/usr/bin/env python3
"""Install the overlay entry point in a kotlin-lsp product descriptor."""

import argparse
import json
from pathlib import Path


OVERLAY_MAIN = "overlay.server.KotlinLspServer"


def configure(product_info: Path, launcher_jar_name: str) -> None:
    product = json.loads(product_info.read_text(encoding="utf-8"))
    launches = product.get("launch")
    if not isinstance(launches, list) or not launches:
        raise ValueError(f"no launch records in {product_info}")

    for launch in launches:
        boot_class_path = launch.get("bootClassPathJarNames")
        if not isinstance(boot_class_path, list):
            raise ValueError(f"launch record has no boot class path in {product_info}")
        if launcher_jar_name not in boot_class_path:
            boot_class_path.append(launcher_jar_name)
        launch["mainClass"] = OVERLAY_MAIN

    product_info.write_text(
        json.dumps(product, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("product_info", type=Path)
    parser.add_argument("launcher_jar_name")
    args = parser.parse_args()
    configure(args.product_info, args.launcher_jar_name)


if __name__ == "__main__":
    main()
