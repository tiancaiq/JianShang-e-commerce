from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Sequence, TextIO

from .marketplace_agent_v2.capabilities import CUSTOMER_CAPABILITIES
from .marketplace_agent_v2.skill_registry import SkillRegistry, SkillValidationError


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="agent")
    commands = parser.add_subparsers(dest="command", required=True)
    skills = commands.add_parser("skills", help="Inspect and validate Agent Skills")
    skills.add_argument("--skills-dir", type=Path, default=None, help=argparse.SUPPRESS)
    skill_commands = skills.add_subparsers(dest="skills_command", required=True)
    skill_commands.add_parser("list", help="List registered Skills")
    show = skill_commands.add_parser("show", help="Show Skill metadata")
    show.add_argument("name")
    show.add_argument("--body", action="store_true", help="Include instructions")
    validate = skill_commands.add_parser("validate", help="Validate the Skill library")
    validate.add_argument("name", nargs="?")
    tools = skill_commands.add_parser("tools", help="List a Skill's allowed tools")
    tools.add_argument("name")
    return parser


def main(
    argv: Sequence[str] | None = None,
    *,
    stdout: TextIO = sys.stdout,
    stderr: TextIO = sys.stderr,
) -> int:
    args = _parser().parse_args(argv)
    known_tools = tuple(item.name for item in CUSTOMER_CAPABILITIES)
    try:
        registry = (
            SkillRegistry.discover(args.skills_dir, known_tools=known_tools)
            if args.skills_dir is not None
            else SkillRegistry.default(known_tools=known_tools)
        )
        if args.skills_command == "list":
            for skill in registry.skills:
                print(skill.name, file=stdout)
            return 0
        if args.skills_command == "show":
            skill = registry.get(args.name)
            print(f"name: {skill.name}", file=stdout)
            print(f"description: {skill.description}", file=stdout)
            print(f"version: {skill.version}", file=stdout)
            print(f"surface: {skill.surface}", file=stdout)
            print("allowed tools:", file=stdout)
            for tool in skill.allowed_tools:
                print(f"  - {tool}", file=stdout)
            print(f"source path: {skill.source_path}", file=stdout)
            if args.body:
                print("\ninstructions:\n", file=stdout)
                print(skill.instructions, file=stdout)
            return 0
        if args.skills_command == "validate":
            if args.name is not None:
                registry.get(args.name)
                print(f"valid: {args.name}", file=stdout)
            else:
                print(f"valid: {len(registry.skills)} skills", file=stdout)
            return 0
        if args.skills_command == "tools":
            for tool in registry.get(args.name).allowed_tools:
                print(tool, file=stdout)
            return 0
    except SkillValidationError as error:
        for issue in error.issues:
            print(f"error: {issue}", file=stderr)
        return 1
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
