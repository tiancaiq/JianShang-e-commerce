from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


_SKILL_NAME = re.compile(r"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
_TOOL_NAME = re.compile(r"^[a-z][a-z0-9_]*$")
_REQUIRED_FIELDS = frozenset({
    "name", "description", "version", "surface", "allowed_tools",
})
_SUPPORTED_SURFACES = frozenset({"customer"})


class SkillValidationError(ValueError):
    """Reports deterministic, customer-safe Skill library validation failures."""

    def __init__(self, issues: Sequence[str]) -> None:
        self.issues = tuple(issues)
        super().__init__("; ".join(self.issues))


@dataclass(frozen=True)
class Skill:
    name: str
    description: str
    version: int
    surface: str
    allowed_tools: tuple[str, ...]
    instructions: str
    source_path: Path

    def compact(self) -> dict[str, object]:
        return {
            "name": self.name,
            "description": self.description,
            "version": self.version,
            "surface": self.surface,
        }

    def model_context(self) -> dict[str, object]:
        return {
            **self.compact(),
            "allowedTools": self.allowed_tools,
            "instructions": self.instructions,
        }


class SkillRegistry:
    """Loads inert Markdown Skills and validates their declared tool boundary."""

    def __init__(self, skills: Iterable[Skill], *, known_tools: Iterable[str]) -> None:
        ordered = tuple(sorted(skills, key=lambda item: item.name))
        issues: list[str] = []
        by_name: dict[str, Skill] = {}
        known = frozenset(known_tools)
        for skill in ordered:
            if skill.name in by_name:
                issues.append(f"duplicate skill name: {skill.name}")
            by_name[skill.name] = skill
            for tool in skill.allowed_tools:
                if tool not in known:
                    issues.append(f"{skill.name}: unknown tool: {tool}")
        if issues:
            raise SkillValidationError(sorted(set(issues)))
        self._skills = ordered
        self._by_name = by_name
        self._known_tools = known

    @classmethod
    def discover(
        cls,
        root: Path,
        *,
        known_tools: Iterable[str],
    ) -> "SkillRegistry":
        if not root.is_dir():
            raise SkillValidationError((f"skill root is not a directory: {root}",))
        issues: list[str] = []
        skills: list[Skill] = []
        discovered_names: set[str] = set()
        for directory in sorted(root.iterdir(), key=lambda item: item.name):
            if directory.name.startswith("."):
                continue
            if not directory.is_dir() or not _SKILL_NAME.fullmatch(directory.name):
                issues.append(f"invalid skill directory: {directory.name}")
                continue
            source = directory / "SKILL.md"
            if not source.is_file():
                issues.append(f"{directory.name}: missing SKILL.md")
                continue
            try:
                skill = _parse_skill(source)
            except SkillValidationError as error:
                issues.extend(error.issues)
                continue
            if skill.name != directory.name:
                issues.append(
                    f"{directory.name}: metadata name must match the directory"
                )
            if skill.name in discovered_names:
                issues.append(f"duplicate skill name: {skill.name}")
            discovered_names.add(skill.name)
            skills.append(skill)
        if issues:
            raise SkillValidationError(sorted(set(issues)))
        return cls(skills, known_tools=known_tools)

    @classmethod
    def default(cls, *, known_tools: Iterable[str]) -> "SkillRegistry":
        return cls.discover(Path(__file__).with_name("skills"), known_tools=known_tools)

    @property
    def skills(self) -> tuple[Skill, ...]:
        return self._skills

    def get(self, name: str) -> Skill:
        skill = self._by_name.get(name)
        if skill is None:
            raise SkillValidationError((f"unknown skill: {name}",))
        return skill

    def available(
        self,
        *,
        surface: str,
        enabled_tools: Iterable[str],
    ) -> tuple[Skill, ...]:
        enabled = frozenset(enabled_tools)
        return tuple(
            skill for skill in self._skills
            if skill.surface == surface and set(skill.allowed_tools) <= enabled
        )

    def selection_schema(self, skills: Sequence[Skill]) -> dict[str, object]:
        if not skills:
            raise ValueError("At least one Skill is required for selection")
        compact = "\n".join(
            f"- {skill.name}: {skill.description}" for skill in skills
        )
        return {
            "type": "function",
            "name": "load_skill",
            "description": (
                "Select one reusable instruction Skill when it materially helps this "
                "marketplace request. This loads instructions only; it performs no I/O "
                "or marketplace action. Available Skills:\n" + compact
            ),
            "strict": True,
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "enum": [skill.name for skill in skills],
                    }
                },
                "required": ["name"],
                "additionalProperties": False,
            },
        }


def _parse_skill(source: Path) -> Skill:
    try:
        raw = source.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise SkillValidationError((f"{source}: unreadable Skill file",)) from error
    lines = raw.splitlines()
    if not lines or lines[0].strip() != "---":
        raise SkillValidationError((f"{source}: missing YAML front matter",))
    try:
        closing = next(
            index for index, line in enumerate(lines[1:], start=1)
            if line.strip() == "---"
        )
    except StopIteration as error:
        raise SkillValidationError((f"{source}: unclosed YAML front matter",)) from error
    metadata = _parse_metadata(lines[1:closing], source)
    body = "\n".join(lines[closing + 1:]).strip()
    issues: list[str] = []
    missing = sorted(_REQUIRED_FIELDS - metadata.keys())
    unexpected = sorted(metadata.keys() - _REQUIRED_FIELDS)
    if missing:
        issues.append(f"{source}: missing metadata fields: {', '.join(missing)}")
    if unexpected:
        issues.append(f"{source}: unsupported metadata fields: {', '.join(unexpected)}")
    name = metadata.get("name")
    description = metadata.get("description")
    version = metadata.get("version")
    surface = metadata.get("surface")
    tools = metadata.get("allowed_tools")
    if not isinstance(name, str) or not _SKILL_NAME.fullmatch(name):
        issues.append(f"{source}: invalid name")
    if not isinstance(description, str) or not 1 <= len(description) <= 300:
        issues.append(f"{source}: invalid description")
    if not isinstance(version, int) or isinstance(version, bool) or version < 1:
        issues.append(f"{source}: version must be a positive integer")
    if not isinstance(surface, str) or surface not in _SUPPORTED_SURFACES:
        issues.append(f"{source}: invalid surface")
    if (
        not isinstance(tools, list)
        or not tools
        or any(not isinstance(tool, str) or not _TOOL_NAME.fullmatch(tool) for tool in tools)
        or len(tools) != len(set(tools))
    ):
        issues.append(f"{source}: allowed_tools must be a unique non-empty tool list")
    if not body:
        issues.append(f"{source}: instructions must not be empty")
    if issues:
        raise SkillValidationError(tuple(issues))
    assert isinstance(name, str)
    assert isinstance(description, str)
    assert isinstance(version, int)
    assert isinstance(surface, str)
    assert isinstance(tools, list)
    return Skill(
        name=name,
        description=description,
        version=version,
        surface=surface,
        allowed_tools=tuple(tools),
        instructions=body,
        source_path=source.resolve(),
    )


def _parse_metadata(lines: Sequence[str], source: Path) -> dict[str, object]:
    metadata: dict[str, object] = {}
    active_list: str | None = None
    for raw in lines:
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        if raw.startswith("  - "):
            if active_list is None:
                raise SkillValidationError((f"{source}: list item without a field",))
            value = raw[4:].strip()
            if not value or any(token in value for token in ("[", "]", "{", "}")):
                raise SkillValidationError((f"{source}: invalid list item",))
            current = metadata[active_list]
            assert isinstance(current, list)
            current.append(value)
            continue
        active_list = None
        if raw[:1].isspace() or ":" not in raw:
            raise SkillValidationError((f"{source}: malformed metadata line",))
        key, value = (part.strip() for part in raw.split(":", 1))
        if not key or key in metadata:
            raise SkillValidationError((f"{source}: duplicate or empty metadata field",))
        if value:
            metadata[key] = int(value) if key == "version" and value.isdigit() else value
        else:
            metadata[key] = []
            active_list = key
    return metadata
