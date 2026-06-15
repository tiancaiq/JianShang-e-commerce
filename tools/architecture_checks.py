#!/usr/bin/env python3
"""Repository architecture guardrails for Phase 1.

The checks are intentionally source based so they run quickly in local
development and CI without starting services.
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path


SERVICE_ROOTS = {
    "api-gateway": "api_gateway",
    "auth-service": "auth_service",
    "inventory-service": "inventory_service",
    "notification-service": "notification_service",
    "order-service": "order_service",
    "payment-service": "payment_service",
    "product-service": "product_service",
}

COMMON_ROOTS = {
    "common-core": "common.core",
    "common-web": "common.web",
    "common-testing": "common.testing",
}

SERVICE_DATABASE_NAMES = {
    "auth-service": {"auth_service", "identity"},
    "inventory-service": {"inventory_service", "inventory"},
    "order-service": {"js_order", "order_service", "orders"},
    "payment-service": {"payment_service", "payments"},
    "product-service": {"product-service", "product_service", "marketplace"},
    "notification-service": {"notifications"},
}

DOMAIN_SEGMENTS_FOR_COMMON = {
    "admin",
    "agent",
    "business",
    "buyer",
    "cart",
    "checkout",
    "inventory",
    "listing",
    "moderation",
    "notification",
    "order",
    "payment",
    "product",
    "review",
    "seller",
    "shipping",
    "store",
    "trade",
}

BANNED_COMMON_PATTERNS = {
    "JPA entity": re.compile(r"@\s*Entity\b|import\s+jakarta\.persistence\."),
    "Spring repository": re.compile(
        r"@\s*Repository\b|import\s+org\.springframework\.stereotype\.Repository"
        r"|JpaRepository|MongoRepository|CrudRepository"
    ),
    "Spring business service": re.compile(
        r"@\s*Service\b|import\s+org\.springframework\.stereotype\.Service"
    ),
    "Spring controller": re.compile(
        r"@\s*(RestController|Controller)\b"
        r"|import\s+org\.springframework\.web\.bind\.annotation\.(RestController|Controller)\s*;"
    ),
}

PACKAGE_RE = re.compile(r"^\s*package\s+([a-zA-Z0-9_.]+)\s*;", re.MULTILINE)
IMPORT_RE = re.compile(r"^\s*import\s+com\.msb\.ecom\.([a-zA-Z0-9_]+)\.", re.MULTILINE)
SPRING_DATASOURCE_URL_RE = re.compile(r"spring\.datasource\.url\s*=\s*(.+)")


@dataclass(frozen=True)
class Violation:
    path: Path
    message: str

    def render(self, root: Path) -> str:
        try:
            display_path = self.path.relative_to(root)
        except ValueError:
            display_path = self.path
        return f"{display_path}: {self.message}"


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def java_files(root: Path, source_root: str = "src/main/java") -> list[Path]:
    base = root / source_root
    if not base.exists():
        return []
    return sorted(base.rglob("*.java"))


def resource_files(root: Path) -> list[Path]:
    base = root / "src/main/resources"
    if not base.exists():
        return []
    return sorted(
        path
        for path in base.rglob("*")
        if path.is_file() and path.suffix.lower() in {".properties", ".sql", ".yml", ".yaml"}
    )


def check_service_package_ownership(repo: Path) -> list[Violation]:
    violations: list[Violation] = []
    for service_root, package_segment in SERVICE_ROOTS.items():
        root = repo / service_root
        for path in java_files(root):
            text = read_text(path)
            match = PACKAGE_RE.search(text)
            expected = f"com.msb.ecom.{package_segment}"
            if not match:
                violations.append(Violation(path, f"missing package declaration; expected {expected}.*"))
                continue
            package_name = match.group(1)
            if package_name != expected and not package_name.startswith(expected + "."):
                violations.append(
                    Violation(path, f"package {package_name} must stay under {expected}")
                )
    return violations


def check_common_package_ownership(repo: Path) -> list[Violation]:
    violations: list[Violation] = []
    for common_root, package_segment in COMMON_ROOTS.items():
        root = repo / common_root
        for path in java_files(root):
            text = read_text(path)
            match = PACKAGE_RE.search(text)
            expected = f"com.msb.ecom.{package_segment}"
            if not match:
                violations.append(Violation(path, f"missing package declaration; expected {expected}.*"))
                continue
            package_name = match.group(1)
            if package_name != expected and not package_name.startswith(expected + "."):
                violations.append(
                    Violation(path, f"package {package_name} must stay under {expected}")
                )
    return violations


def check_service_import_boundaries(repo: Path) -> list[Violation]:
    violations: list[Violation] = []
    service_segments = set(SERVICE_ROOTS.values())
    for service_root, own_segment in SERVICE_ROOTS.items():
        root = repo / service_root
        for path in java_files(root):
            text = read_text(path)
            for imported_segment in IMPORT_RE.findall(text):
                if imported_segment in service_segments and imported_segment != own_segment:
                    violations.append(
                        Violation(
                            path,
                            f"imports another service package com.msb.ecom.{imported_segment}; use an API or event contract",
                        )
                    )
    return violations


def check_common_module_purity(repo: Path) -> list[Violation]:
    violations: list[Violation] = []
    for common_root in COMMON_ROOTS:
        root = repo / common_root
        for path in java_files(root):
            text = read_text(path)
            for label, pattern in BANNED_COMMON_PATTERNS.items():
                if pattern.search(text):
                    violations.append(Violation(path, f"common module contains {label}"))
            match = PACKAGE_RE.search(text)
            if match:
                package_segments = set(match.group(1).split("."))
                domain_segments = sorted(package_segments & DOMAIN_SEGMENTS_FOR_COMMON)
                if domain_segments:
                    violations.append(
                        Violation(
                            path,
                            "common module package contains domain segment(s): "
                            + ", ".join(domain_segments),
                        )
                    )
    return violations


def check_service_database_ownership(repo: Path) -> list[Violation]:
    violations: list[Violation] = []
    all_database_names = set().union(*SERVICE_DATABASE_NAMES.values())
    for service_root, own_names in SERVICE_DATABASE_NAMES.items():
        root = repo / service_root
        other_names = all_database_names - own_names
        for path in resource_files(root):
            text = read_text(path)
            for match in SPRING_DATASOURCE_URL_RE.finditer(text):
                url = match.group(1)
                for database_name in sorted(other_names):
                    if f"/{database_name}" in url or f":{database_name}" in url:
                        violations.append(
                            Violation(
                                path,
                                f"datasource URL references another service database '{database_name}'",
                            )
                        )
            if path.suffix.lower() == ".sql":
                for database_name in sorted(other_names):
                    if re.search(rf"\b{re.escape(database_name)}\.", text):
                        violations.append(
                            Violation(
                                path,
                                f"SQL references another service schema '{database_name}'",
                            )
                        )
    return violations


def run_checks(repo: Path) -> list[Violation]:
    checks = [
        check_service_package_ownership,
        check_common_package_ownership,
        check_service_import_boundaries,
        check_common_module_purity,
        check_service_database_ownership,
    ]
    violations: list[Violation] = []
    for check in checks:
        violations.extend(check(repo))
    return violations


def write_file(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def run_self_test() -> int:
    with tempfile.TemporaryDirectory() as temp_dir:
        repo = Path(temp_dir)
        write_file(
            repo / "order-service/src/main/java/com/msb/ecom/order_service/BadImport.java",
            """
            package com.msb.ecom.order_service;
            import com.msb.ecom.payment_service.repository.PaymentRepository;
            class BadImport {}
            """,
        )
        write_file(
            repo / "common-core/src/main/java/com/msb/ecom/common/core/order/BadOrder.java",
            """
            package com.msb.ecom.common.core.order;
            import jakarta.persistence.Entity;
            @Entity
            class BadOrder {}
            """,
        )
        write_file(
            repo / "inventory-service/src/main/resources/application.properties",
            "spring.datasource.url=jdbc:mysql://localhost:3306/payment_service\n",
        )

        violations = run_checks(repo)
        messages = "\n".join(violation.render(repo) for violation in violations)
        required_fragments = [
            "imports another service package",
            "common module contains JPA entity",
            "common module package contains domain segment",
            "datasource URL references another service database",
        ]
        missing = [fragment for fragment in required_fragments if fragment not in messages]
        if missing:
            print("Architecture check self-test failed.")
            print("Missing expected violation(s): " + ", ".join(missing))
            print(messages)
            return 1
        print("architecture_self_test_ok")
        return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Run MSB E-Commerce architecture checks.")
    parser.add_argument("--repo", default=".", help="Repository root. Defaults to current directory.")
    parser.add_argument("--self-test", action="store_true", help="Run controlled violation examples.")
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_test()

    repo = Path(args.repo).resolve()
    violations = run_checks(repo)
    if violations:
        print("Architecture guardrail violations found:")
        for violation in violations:
            print("- " + violation.render(repo))
        return 1

    print("architecture_checks_ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
