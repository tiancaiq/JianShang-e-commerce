import datetime as dt
import hashlib
import mimetypes
import os
import pathlib
import subprocess
import sys


ROOT = pathlib.Path(r"C:\Users\b\Downloads\Downloads\04-03 碧蓝航线 大凤  269p")
MYSQL_CONTAINER = os.environ.get("SEED_MYSQL_CONTAINER", "mysql")
MYSQL_PASSWORD = os.environ.get("SEED_MYSQL_PASSWORD", "mysql")
MYSQL_ARGS = ["mysql", "-uroot", f"-p{MYSQL_PASSWORD}", "--default-character-set=utf8mb4", "-N", "-B"]
CATALOG_DB = "catalog"
IDENTITY_DB = "identity"
SEED_MARKER = "Taihou Seed"
CITY = "Irvine"
REGION = "Orange County"
BUCKET_DEFAULT = "jianshang"
CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def load_env():
    values = {}
    for name in [".env.demo", ".env"]:
        path = pathlib.Path(name)
        if not path.exists():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            key = key.strip()
            value = value.strip().strip('"').strip("'")
            if value.startswith("demo-change-me") and key in values:
                continue
            values[key] = value
    values.update({k: v for k, v in os.environ.items() if k.startswith("S3_") or k == "LISTING_MEDIA_STORAGE"})
    return values


def mysql_query(sql):
    result = subprocess.run(
        ["docker", "exec", "-i", MYSQL_CONTAINER, *MYSQL_ARGS, "-e", sql],
        text=True,
        capture_output=True,
        check=True,
    )
    return [line.split("\t") for line in result.stdout.splitlines() if line.strip()]


def mysql_exec(sql):
    subprocess.run(
        ["docker", "exec", "-i", MYSQL_CONTAINER, *MYSQL_ARGS],
        input=sql,
        text=True,
        check=True,
    )


def sql_string(value):
    if value is None:
        return "NULL"
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


def sql_number(value):
    return str(value)


def seeded_id(kind, value):
    digest = hashlib.sha256((kind + ":" + value).encode("utf-8")).digest()
    number = int.from_bytes(digest, "big")
    chars = []
    for _ in range(26):
        chars.append(CROCKFORD[number & 31])
        number >>= 5
    return "".join(reversed(chars))


def file_sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def java_upload_classpath():
    helper_dir = pathlib.Path("target/seed-tools").resolve()
    storage_classes = pathlib.Path("common-storage/target/classes").resolve()
    dependency_cp = pathlib.Path("common-storage/target/seed-cp.txt")
    helper_class = helper_dir / "SeedTaihouUpload.class"
    if not helper_class.exists():
        raise RuntimeError("SeedTaihouUpload.class is missing. Compile tools/SeedTaihouUpload.java first.")
    if not dependency_cp.exists():
        raise RuntimeError("common-storage/target/seed-cp.txt is missing. Build common-storage classpath first.")
    parts = [str(helper_dir), str(storage_classes), dependency_cp.read_text(encoding="utf-8").strip()]
    return os.pathsep.join(part for part in parts if part)


def upload_object(path, object_key, env):
    bucket = env.get("S3_BUCKET", BUCKET_DEFAULT)
    content_type = mimetypes.guess_type(path.name)[0] or "image/jpeg"
    result = subprocess.run(
        ["java", "-cp", java_upload_classpath(), "SeedTaihouUpload", str(path), object_key, content_type],
        text=True,
        capture_output=True,
    )
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip().splitlines()
        reason = detail[-1] if detail else "Java uploader failed without output."
        raise RuntimeError(f"Upload failed for {path.name}: {reason}")
    return bucket, content_type, path.stat().st_size


def long_description(index, seller_type, checksum, original_name):
    paragraphs = [
        f"{SEED_MARKER} {seller_type.lower()} training listing {index:03d}. This seeded marketplace record is intentionally verbose so future AI features can learn from long-form item text, field variety, moderation-safe wording, and buyer-facing commerce language.",
        "The description is deliberately not tied to the exact image content. It talks about condition expectations, pickup planning, message etiquette, availability, packaging notes, and how a buyer might compare similar marketplace entries without relying only on the picture.",
        "Use this listing as synthetic catalog text for search ranking, summarization, semantic matching, duplicate detection, and listing quality experiments. The copy includes city and county language, price negotiation hints, fulfillment context, and a stable data marker for later cleanup.",
        f"Original local file name: {original_name}. Source checksum: {checksum}. Seed notes: no payment is handled by the platform for individual trade listings; business store items are self-published seed data for storefront search and card rendering.",
    ]
    return "\n\n".join(paragraphs)


def existing_checksums():
    rows = mysql_query(f"select lower(checksum_sha256) from {CATALOG_DB}.listing_media_objects where checksum_sha256 is not null")
    return {row[0] for row in rows}


def first_active_seller():
    rows = mysql_query(f"""
        select u.id
        from {IDENTITY_DB}.users u
        join {IDENTITY_DB}.individual_seller_profiles isp on isp.user_id = u.id
        where isp.status = 'ACTIVE'
        order by u.created_at
        limit 1
    """)
    if not rows:
        raise RuntimeError("No active individual seller profile found.")
    return rows[0][0]


def first_approved_application():
    rows = mysql_query(f"""
        select id, applicant_user_id, legal_name, business_type, country, contact_email, contact_phone, description
        from {IDENTITY_DB}.business_applications
        where status = 'APPROVED'
        order by created_at
        limit 1
    """)
    if not rows:
        raise RuntimeError("No approved business application found.")
    return rows[0]


def active_categories():
    rows = mysql_query(f"select id from {CATALOG_DB}.categories where status='ACTIVE' order by display_order, id")
    if not rows:
        raise RuntimeError("No active categories found.")
    return [row[0] for row in rows]


def build_seed_sql(records, seller_user_id, business, categories, bucket):
    app_id, applicant_user_id, legal_name, business_type, country, contact_email, contact_phone, app_description = business
    business_id = seeded_id("business", app_id)
    store_id = seeded_id("store", business_id)
    now = dt.datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S.000000")
    stmts = ["SET NAMES utf8mb4;", "START TRANSACTION;"]

    stmts.append(f"""
        INSERT IGNORE INTO {IDENTITY_DB}.businesses (
            id, application_id, legal_name, business_type, country, status,
            approved_at, approved_by, version, created_at, updated_at
        ) VALUES (
            {sql_string(business_id)}, {sql_string(app_id)}, {sql_string(legal_name or 'Taihou Seed Business')},
            {sql_string(business_type or 'LLC')}, {sql_string(country or 'US')}, 'ACTIVE',
            {sql_string(now)}, {sql_string(seller_user_id)}, 0, {sql_string(now)}, {sql_string(now)}
        );
    """)
    stmts.append(f"""
        INSERT IGNORE INTO {IDENTITY_DB}.business_memberships (
            business_id, user_id, role, status, invited_by, created_at, updated_at
        ) VALUES (
            {sql_string(business_id)}, {sql_string(seller_user_id)}, 'OWNER', 'ACTIVE', NULL,
            {sql_string(now)}, {sql_string(now)}
        );
    """)
    stmts.append(f"""
        INSERT IGNORE INTO {IDENTITY_DB}.stores (
            id, business_id, slug, name, description, logo_url, banner_url,
            support_email, support_phone, status, version, created_at, updated_at
        ) VALUES (
            {sql_string(store_id)}, {sql_string(business_id)}, 'taihou-seed-store',
            {sql_string('Taihou Seed Store')},
            {sql_string((app_description or 'Seed business store for local marketplace image data.')[:1000])},
            NULL, NULL, {sql_string(contact_email)}, {sql_string(contact_phone)},
            'ACTIVE', 0, {sql_string(now)}, {sql_string(now)}
        );
    """)
    stmts.append(f"""
        UPDATE {IDENTITY_DB}.business_applications
        SET approved_business_id = COALESCE(approved_business_id, {sql_string(business_id)}),
            reviewer_user_id = COALESCE(reviewer_user_id, {sql_string(seller_user_id)}),
            decision_reason = COALESCE(decision_reason, 'Seed business for local marketplace data.'),
            decided_at = COALESCE(decided_at, {sql_string(now)}),
            updated_at = {sql_string(now)}
        WHERE id = {sql_string(app_id)};
    """)

    half = (len(records) + 1) // 2
    conditions = ["GOOD", "LIKE_NEW", "NEW", "OPEN_BOX", "FAIR"]
    for index, record in enumerate(records, start=1):
        seller_type = "INDIVIDUAL" if index <= half else "BUSINESS"
        rel_index = index if seller_type == "INDIVIDUAL" else index - half
        listing_id = seeded_id("listing", record["sha256"])
        media_id = seeded_id("media", record["sha256"])
        image_id = seeded_id("image", record["sha256"])
        category_id = categories[(index - 1) % len(categories)]
        title_prefix = "IND" if seller_type == "INDIVIDUAL" else "BUS"
        title = f"{SEED_MARKER} {title_prefix} {rel_index:03d}"
        condition = conditions[(index - 1) % len(conditions)]
        description = long_description(index, seller_type, record["sha256"], record["name"])
        price = f"{(8 + (index % 73) + (index % 9) / 10):.4f}"
        quantity = 1 if seller_type == "INDIVIDUAL" else 3 + (index % 18)
        sku = None if seller_type == "INDIVIDUAL" else f"TAIHOU-SEED-{rel_index:03d}"
        publication_source = "ADMIN_REVIEW" if seller_type == "INDIVIDUAL" else "BUSINESS_SELF_PUBLISHED"
        moderation_status = "APPROVED" if seller_type == "INDIVIDUAL" else "NOT_SUBMITTED"
        individual_id = seller_user_id if seller_type == "INDIVIDUAL" else None
        row_business_id = None if seller_type == "INDIVIDUAL" else business_id
        row_store_id = None if seller_type == "INDIVIDUAL" else store_id
        negotiable = 1 if seller_type == "INDIVIDUAL" else 0
        object_key = record["object_key"]

        stmts.append(f"""
            INSERT IGNORE INTO {CATALOG_DB}.listings (
                id, seller_type, individual_seller_user_id, business_id, store_id, category_id,
                title, description, condition_code, condition_notes, price_amount, currency,
                negotiable, sku, quantity, public_city, public_region,
                payment_preferences_json, delivery_preferences_json, status, moderation_status,
                publication_source, published_at, version, created_at, updated_at
            ) VALUES (
                {sql_string(listing_id)}, {sql_string(seller_type)}, {sql_string(individual_id)},
                {sql_string(row_business_id)}, {sql_string(row_store_id)}, {sql_string(category_id)},
                {sql_string(title)}, {sql_string(description)}, {sql_string(condition)},
                {sql_string('Seed condition notes for AI training and marketplace layout testing.')},
                {sql_number(price)}, 'USD', {sql_number(negotiable)}, {sql_string(sku)}, {sql_number(quantity)},
                {sql_string(CITY)}, {sql_string(REGION)}, NULL, NULL, 'ACTIVE',
                {sql_string(moderation_status)}, {sql_string(publication_source)}, {sql_string(now)},
                0, {sql_string(now)}, {sql_string(now)}
            );
        """)
        stmts.append(f"""
            INSERT IGNORE INTO {CATALOG_DB}.listing_media_objects (
                id, listing_id, seller_type, individual_seller_user_id, business_id,
                object_bucket, object_key, original_file_name, content_type, size_bytes,
                checksum_sha256, upload_status, moderation_status, version, created_at, updated_at
            ) VALUES (
                {sql_string(media_id)}, {sql_string(listing_id)}, {sql_string(seller_type)},
                {sql_string(individual_id)}, {sql_string(row_business_id)}, {sql_string(bucket)},
                {sql_string(object_key)}, {sql_string(record["name"])}, {sql_string(record["content_type"])},
                {sql_number(record["size"])}, {sql_string(record["sha256"])}, 'UPLOADED', 'APPROVED',
                0, {sql_string(now)}, {sql_string(now)}
            );
        """)
        stmts.append(f"""
            INSERT IGNORE INTO {CATALOG_DB}.listing_images (
                id, listing_id, media_object_id, display_order, alt_text, moderation_status,
                version, created_at, updated_at
            ) VALUES (
                {sql_string(image_id)}, {sql_string(listing_id)}, {sql_string(media_id)}, 0,
                {sql_string(title)}, 'APPROVED', 0, {sql_string(now)}, {sql_string(now)}
            );
        """)
        stmts.append(f"""
            INSERT IGNORE INTO {CATALOG_DB}.listing_engagement_stats (
                listing_id, visit_count, like_count, updated_at
            ) VALUES ({sql_string(listing_id)}, 0, 0, {sql_string(now)});
        """)

    stmts.append("COMMIT;")
    return "\n".join(stmts)


def main():
    if not ROOT.exists():
        raise RuntimeError(f"Image folder not found: {ROOT}")
    env = load_env()
    if env.get("LISTING_MEDIA_STORAGE") != "s3":
        raise RuntimeError("LISTING_MEDIA_STORAGE must be s3 for this seed upload.")
    bucket = env.get("S3_BUCKET", BUCKET_DEFAULT)
    paths = sorted(
        [p for p in ROOT.rglob("*") if p.is_file() and p.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}],
        key=lambda p: str(p.relative_to(ROOT)).lower(),
    )
    existing = existing_checksums()
    prepared = []
    seen = set()
    for path in paths:
        checksum = file_sha256(path)
        if checksum in seen or checksum in existing:
            continue
        seen.add(checksum)
        prepared.append({
            "path": path,
            "name": path.name,
            "sha256": checksum,
        })

    if not prepared:
        print("No new images to seed; all image checksums already exist in listing media.")
        return

    half = (len(prepared) + 1) // 2
    skip_storage_upload = os.environ.get("SEED_SKIP_STORAGE_UPLOAD", "").lower() in {"1", "true", "yes"}
    for index, record in enumerate(prepared, start=1):
        path = record["path"]
        listing_id = seeded_id("listing", record["sha256"])
        media_id = seeded_id("media", record["sha256"])
        seller_type = "individual" if index <= half else "business"
        object_key = f"seed/taihou/{seller_type}/{listing_id}/{media_id}.jpg"
        if skip_storage_upload:
            content_type = mimetypes.guess_type(path.name)[0] or "image/jpeg"
            size = path.stat().st_size
        else:
            upload_bucket, content_type, size = upload_object(path, object_key, env)
        record["path"] = str(path)
        record["content_type"] = content_type
        record["size"] = size
        record["object_key"] = object_key
        if not skip_storage_upload and index % 25 == 0:
            print(f"Uploaded {index} images...")

    seller_user_id = first_active_seller()
    business = first_approved_application()
    categories = active_categories()
    sql = build_seed_sql(prepared, seller_user_id, business, categories, bucket)
    mysql_exec(sql)
    half = (len(prepared) + 1) // 2
    print(f"Seed complete: {len(prepared)} listings/images inserted or already present.")
    print(f"Individual listings targeted: {half}; business listings targeted: {len(prepared) - half}.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"Seed failed: {exc}", file=sys.stderr)
        sys.exit(1)
