from __future__ import annotations

import hashlib
import math
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

from .schemas import HelpKnowledgePassage


_ARTICLE_ID = re.compile(r"^HELP-[A-Z0-9-]{3,80}$")
_WORD = re.compile(r"[^\W_]+", re.UNICODE)
_HEADING = re.compile(r"^(?P<level>#{1,3})\s+(?P<title>.+?)\s*$")
_STOP_WORDS = frozenset({
    "a", "an", "and", "are", "as", "at", "be", "can", "do", "does",
    "for", "from", "get", "has", "have", "how", "i", "in", "is", "it",
    "me", "my", "of", "on", "or", "that", "the", "this", "to", "what",
    "when", "where", "which", "with", "you", "your",
})


class HelpKnowledgeConfigurationError(RuntimeError):
    """Rejects an absent or malformed public help corpus at startup."""


@dataclass(frozen=True)
class _HelpSection:
    article_id: str
    title: str
    section: str
    audience: str
    availability: str
    text: str
    version: str
    tokens: Counter[str]
    title_tokens: frozenset[str]


class HelpKnowledgeRetriever:
    """Loads approved public help Markdown and performs bounded lexical retrieval."""

    def __init__(self, corpus_path: str | Path) -> None:
        path = Path(corpus_path)
        if not path.is_dir():
            raise HelpKnowledgeConfigurationError(
                "Marketplace help corpus directory does not exist"
            )
        sections: list[_HelpSection] = []
        article_ids: set[str] = set()
        for source in sorted(path.glob("*.md")):
            parsed = _parse_article(source)
            if parsed is None:
                continue
            metadata, body = parsed
            article_id = metadata["article_id"]
            if article_id in article_ids:
                raise HelpKnowledgeConfigurationError(
                    "Marketplace help article IDs must be unique"
                )
            article_ids.add(article_id)
            sections.extend(_sections(metadata, body))
        if not sections:
            raise HelpKnowledgeConfigurationError(
                "Marketplace help corpus contains no public articles"
            )
        self._sections = tuple(sections)
        self._document_frequency = Counter(
            token
            for section in self._sections
            for token in set(section.tokens)
        )

    @property
    def article_count(self) -> int:
        return len({item.article_id for item in self._sections})

    def retrieve(self, query: str, *, limit: int = 3) -> tuple[HelpKnowledgePassage, ...]:
        normalized_query = " ".join(query.split())
        query_tokens = _tokens(normalized_query)
        if not query_tokens or not 1 <= limit <= 3:
            return ()
        query_counts = Counter(query_tokens)
        corpus_size = len(self._sections)
        scored: list[tuple[float, _HelpSection]] = []
        for section in self._sections:
            score = 0.0
            for token, frequency in query_counts.items():
                section_frequency = section.tokens.get(token, 0)
                if section_frequency == 0:
                    continue
                inverse_frequency = math.log(
                    1 + corpus_size / (1 + self._document_frequency[token])
                )
                score += inverse_frequency * min(section_frequency, 3) * frequency
                if token in section.title_tokens:
                    score += 2.5 * inverse_frequency
            phrase = normalized_query.casefold()
            searchable_title = f"{section.title} {section.section}".casefold()
            if len(phrase) >= 4 and phrase in searchable_title:
                score += 8.0
            if score > 0:
                scored.append((score, section))
        scored.sort(key=lambda item: (-item[0], item[1].article_id, item[1].section))

        result: list[HelpKnowledgePassage] = []
        seen_articles: set[str] = set()
        for score, section in scored:
            if score < 0.9:
                continue
            # One best section per article keeps model context diverse and bounded.
            if section.article_id in seen_articles:
                continue
            seen_articles.add(section.article_id)
            result.append(HelpKnowledgePassage(
                articleId=section.article_id,
                title=section.title,
                section=section.section,
                audience=section.audience,
                availability=section.availability,
                excerpt=_excerpt(section.text),
                version=section.version,
            ))
            if len(result) >= limit:
                break
        return tuple(result)


def _parse_article(path: Path) -> tuple[dict[str, str], str] | None:
    raw = path.read_text(encoding="utf-8")
    if len(raw.encode("utf-8")) > 128_000:
        raise HelpKnowledgeConfigurationError("Marketplace help article is too large")
    if not raw.startswith("---\n"):
        # README and other non-article Markdown are intentionally not retrievable.
        return None
    boundary = raw.find("\n---\n", 4)
    if boundary < 0:
        raise HelpKnowledgeConfigurationError("Marketplace help front matter is invalid")
    metadata: dict[str, str] = {}
    for line in raw[4:boundary].splitlines():
        key, separator, value = line.partition(":")
        if not separator or not key.strip() or not value.strip():
            raise HelpKnowledgeConfigurationError(
                "Marketplace help front matter must use scalar key/value fields"
            )
        metadata[key.strip()] = value.strip()
    if metadata.get("internal", "false").casefold() == "true":
        return None
    required = {"article_id", "title", "audience", "availability"}
    if not required <= metadata.keys() or not _ARTICLE_ID.fullmatch(
        metadata.get("article_id", "")
    ):
        raise HelpKnowledgeConfigurationError(
            "Public marketplace help articles require valid stable metadata"
        )
    return metadata, raw[boundary + 5 :].strip()


def _sections(metadata: dict[str, str], body: str) -> tuple[_HelpSection, ...]:
    article_version = hashlib.sha256(body.encode("utf-8")).hexdigest()
    article_title = metadata["title"]
    chunks: list[tuple[str, list[str]]] = []
    current_title = article_title
    current_lines: list[str] = []
    for line in body.splitlines():
        heading = _HEADING.match(line)
        if heading is not None:
            if current_lines:
                chunks.append((current_title, current_lines))
            current_title = heading.group("title").strip()
            current_lines = []
            continue
        if line.strip():
            current_lines.append(line.strip())
    if current_lines:
        chunks.append((current_title, current_lines))
    result: list[_HelpSection] = []
    for section_title, lines in chunks:
        # Question lists help humans scan an article but do not contain answers;
        # never let them displace the article's instructional sections.
        if section_title.casefold() == "common questions":
            continue
        text = "\n".join(lines).strip()
        if not text:
            continue
        searchable = f"{article_title} {section_title} {text}"
        result.append(_HelpSection(
            article_id=metadata["article_id"],
            title=article_title,
            section=section_title,
            audience=metadata["audience"],
            availability=metadata["availability"],
            text=text,
            version=article_version,
            tokens=Counter(_tokens(searchable)),
            title_tokens=frozenset(_tokens(f"{article_title} {section_title}")),
        ))
    return tuple(result)


def _tokens(value: str) -> tuple[str, ...]:
    result: list[str] = []
    for raw in _WORD.findall(value.casefold()):
        if raw in _STOP_WORDS or len(raw) < 2:
            continue
        token = raw
        if len(token) > 5 and token.endswith("ing"):
            token = token[:-3]
        elif len(token) > 4 and token.endswith("ed"):
            token = token[:-2]
        elif len(token) > 4 and token.endswith("es"):
            token = token[:-2]
        elif len(token) > 3 and token.endswith("s"):
            token = token[:-1]
        result.append(token)
    return tuple(result)


def _excerpt(value: str) -> str:
    normalized = re.sub(r"\n{3,}", "\n\n", value).strip()
    if len(normalized) <= 2_400:
        return normalized
    boundary = normalized.rfind(" ", 0, 2_397)
    return normalized[: boundary if boundary > 1_800 else 2_397].rstrip() + "..."
