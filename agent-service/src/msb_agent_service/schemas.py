from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class TextSmokeResult(StrictModel):
    language: str = Field(min_length=2, max_length=40)
    summary: str = Field(min_length=1, max_length=500)


class ImageSmokeResult(StrictModel):
    visible_objects: list[str]
    uncertainties: list[str]
    warnings: list[str]


class DemoListingArguments(StrictModel):
    listing_id: str = Field(min_length=1, max_length=100)


class ToolSmokeResult(StrictModel):
    listing_id: str = Field(min_length=1, max_length=100)
    answer: str = Field(min_length=1, max_length=500)
    source: Literal["get_demo_listing"]


class HealthResponse(StrictModel):
    status: Literal["UP"]
    service: Literal["agent-service"]


class ReadinessResponse(StrictModel):
    status: Literal["READY", "NOT_READY"]
    openai: Literal["CONFIGURED", "OPENAI_API_KEY_NOT_CONFIGURED"]
    knowledge_index: Literal[
        "DISABLED",
        "READY",
        "NOT_CONFIGURED",
        "UNAVAILABLE",
        "UNAUTHORIZED",
        "INCOMPATIBLE",
    ] = Field(alias="knowledgeIndex")
    knowledge_ingestion: Literal[
        "DISABLED",
        "READY",
        "UNAVAILABLE",
    ] = Field(alias="knowledgeIngestion")
    category_guidance_intake: Literal[
        "DISABLED",
        "READY",
        "UNAVAILABLE",
    ] = Field(alias="categoryGuidanceIntake")
    agent_persistence: Literal[
        "DISABLED",
        "READY",
        "UNAVAILABLE",
    ] = Field(alias="agentPersistence")
    customer_service_api: Literal[
        "DISABLED",
        "READY",
        "UNAVAILABLE",
        "ORCHESTRATION_DEFERRED",
    ] = Field(alias="customerServiceApi")


Ulid = Annotated[str, StringConstraints(pattern=r"^[0-9A-Z]{26}$")]


class AgentSubjectRequest(StrictModel):
    type: Literal["LISTING"]
    id: Ulid


class CreateAgentSessionRequest(StrictModel):
    session_type: Literal["LISTING_CUSTOMER_SERVICE"] = Field(alias="sessionType")
    subject: AgentSubjectRequest


class SendAgentMessageRequest(StrictModel):
    client_message_id: Ulid = Field(alias="clientMessageId")
    body: str = Field(min_length=1, max_length=8_000)


class AgentSubjectListingResponse(StrictModel):
    id: Ulid
    version: str = Field(min_length=1, max_length=40)
    title: str = Field(min_length=1, max_length=180)
    thumbnail_url: str | None = Field(alias="thumbnailUrl", max_length=1_000)
    transaction_notice: str = Field(
        alias="transactionNotice",
        min_length=1,
        max_length=1_000,
    )


class AgentSessionResponse(StrictModel):
    id: Ulid
    session_type: Literal["LISTING_CUSTOMER_SERVICE"] = Field(alias="sessionType")
    status: Literal["OPEN", "READ_ONLY", "CLOSED"]
    subject_listing: AgentSubjectListingResponse = Field(alias="subjectListing")
    created_at: datetime = Field(alias="createdAt")
    updated_at: datetime = Field(alias="updatedAt")


class AgentSourceResponse(StrictModel):
    source_type: Literal[
        "LISTING",
        "MARKETPLACE_POLICY",
        "SAFETY_GUIDANCE",
        "MARKETPLACE_FAQ",
        "CATEGORY_GUIDANCE",
    ] = Field(alias="sourceType")
    source_id: str = Field(alias="sourceId", min_length=1, max_length=100)
    source_version: str = Field(alias="sourceVersion", min_length=1, max_length=80)
    label: str = Field(min_length=1, max_length=160)


class AgentActionResponse(StrictModel):
    type: Literal["MESSAGE_SELLER", "VIEW_LISTING", "BROWSE_MARKETPLACE"]
    listing_id: Ulid | None = Field(default=None, alias="listingId")

    @model_validator(mode="after")
    def validate_target(self) -> "AgentActionResponse":
        if self.type == "BROWSE_MARKETPLACE" and self.listing_id is not None:
            raise ValueError("BROWSE_MARKETPLACE must not include listingId")
        if self.type != "BROWSE_MARKETPLACE" and self.listing_id is None:
            raise ValueError(f"{self.type} requires listingId")
        return self


class AgentMessageResponse(StrictModel):
    id: Ulid
    role: Literal["USER", "ASSISTANT"]
    body: str = Field(min_length=1, max_length=12_000)
    resolution_type: Literal[
        "ANSWERED",
        "PARTIAL",
        "UNKNOWN",
        "CONTACT_SELLER",
        "REFUSED",
    ] | None = Field(default=None, alias="resolutionType")
    sources: list[AgentSourceResponse] = Field(default_factory=list)
    actions: list[AgentActionResponse] = Field(default_factory=list)
    created_at: datetime = Field(alias="createdAt")


class SendAgentMessageResponse(StrictModel):
    user_message: AgentMessageResponse = Field(alias="userMessage")
    assistant_message: AgentMessageResponse = Field(alias="assistantMessage")


class AgentMessagePageResponse(StrictModel):
    data: list[AgentMessageResponse]
    next_cursor: str | None = Field(alias="nextCursor")
    has_more: bool = Field(alias="hasMore")


class ApiErrorDetail(StrictModel):
    field: str | None = None
    message: str


class ApiErrorBody(StrictModel):
    code: str
    message: str
    details: list[ApiErrorDetail] = Field(default_factory=list)
    correlation_id: str = Field(alias="correlationId")


class ApiErrorEnvelope(StrictModel):
    error: ApiErrorBody
