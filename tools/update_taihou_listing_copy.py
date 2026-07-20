import datetime as dt
import hashlib
import os
import random
import subprocess
import sys


MYSQL_CONTAINER = os.environ.get("SEED_MYSQL_CONTAINER", "msb-demo-mysql-check")
MYSQL_PASSWORD = os.environ.get("SEED_MYSQL_PASSWORD", "demo-change-me-mysql")
MYSQL_ARGS = ["mysql", "-uroot", f"-p{MYSQL_PASSWORD}", "--default-character-set=utf8mb4", "-N", "-B"]

BRANDS = [
    "Northline", "Mochi House", "Amber Loop", "Cloud & Pine", "NekoWorks", "Hearthlane",
    "Pixel Harbor", "Orchid Bay", "Sunday Cart", "Mint Circuit", "Little Metro", "Kumo Studio",
    "Bright Fold", "Luna & Co.", "Juniper Desk", "Everbeam", "Pocket Atlas", "Riverbell",
    "Cotton Signal", "Urban Nori", "Bluecrate", "Paper Lantern", "SoftBrick", "Nova Shelf",
]
ITEMS_BY_CATEGORY = {
    "Electronics": [
        "portable bluetooth speaker", "USB-C docking hub", "mechanical keyboard kit", "tablet stand",
        "noise-isolating earbuds", "1080p webcam", "LED desk lamp", "wireless charging pad",
        "compact travel router", "e-reader sleeve bundle", "mini photo printer", "streaming microphone",
    ],
    "Home & Garden": [
        "ceramic ramen bowl set", "bamboo drawer organizer", "cotton throw blanket", "plant mister",
        "stackable storage basket", "bedside reading lamp", "linen cushion cover pair", "scented candle trio",
        "wall hook rail", "tea tin collection", "small entryway mirror", "folding lap tray",
    ],
    "Clothing": [
        "oversized hoodie", "canvas tote bag", "lightweight windbreaker", "embroidered cap",
        "pleated skirt", "cotton pajama set", "knit cardigan", "crossbody sling bag",
        "graphic tee", "scarf and glove set", "denim overshirt", "soft fleece pullover",
    ],
    "Books & Media": [
        "hardcover art book", "manga volume bundle", "vinyl soundtrack record", "language workbook set",
        "photography zine pack", "strategy guide", "paperback novel lot", "children's picture book set",
        "collector magazine issue", "movie postcard box", "blank sketchbook bundle", "recipe notebook",
    ],
    "General": [
        "acrylic desk organizer", "travel toiletry pouch", "stainless lunch box", "mini tool roll",
        "board game accessory tray", "stationery surprise bundle", "camera strap", "folding picnic mat",
        "insulated water bottle", "puzzle storage case", "key organizer", "craft supply box",
    ],
}
COLORS = [
    "sage green", "milk white", "midnight blue", "warm gray", "rose pink", "walnut brown",
    "butter yellow", "matte black", "sky lavender", "cream beige", "clear smoke", "cobalt",
]
DETAILS = [
    "with original packaging", "with a small accessory pouch", "from a smoke-free home",
    "lightly used for a short setup", "kept in a storage bin", "good for dorm or office use",
    "easy to carry and store", "cleaned before listing", "simple, practical, and not bulky",
    "works best for everyday use", "nice starter piece", "solid backup item",
]
CONDITION_DETAILS = [
    "I checked the corners, seams, edges, and visible surfaces before listing it.",
    "There may be small signs of handling, but nothing that affects normal use.",
    "The most visible wear is cosmetic and easier to notice up close than in regular use.",
    "I would call it clean enough to gift casually, though not sealed-new unless the condition says new.",
    "Please expect normal shelf or storage marks instead of perfect retail condition.",
    "It has been stored indoors and handled with reasonable care.",
]
USE_CASES = [
    "a desk refresh", "a first apartment setup", "school supplies", "a compact bedroom",
    "a weekend convention bag", "packing small supplies", "a reading corner", "daily commuting",
    "a collector shelf", "a spare office setup", "a gift basket", "organizing a hobby space",
]
CATEGORY_DETAIL_BANK = {
    "Electronics": {
        "features": [
            "I tested the basic power and everyday controls during inspection.",
            "Good for a cleaner desk setup, travel bag, streaming corner, or backup device drawer.",
            "Please bring your own cable or adapter if you need to test a very specific setup.",
            "The casing, buttons, ports, and visible edges were checked for normal handling wear.",
        ],
        "buyer_checks": [
            "Ask about cable type, device compatibility, or dimensions before pickup.",
            "If you use a specific laptop, tablet, console, or phone, message me first so we can compare requirements.",
            "This is best for someone who wants a practical accessory rather than a sealed retail box.",
        ],
    },
    "Home & Garden": {
        "features": [
            "The surface and usable areas were wiped down during inspection.",
            "Works well for a small apartment, kitchen counter, bedside shelf, entry table, or plant corner.",
            "The material feels sturdy enough for normal household use without taking up much space.",
            "I checked for chips, cracks, loose parts, stains, and storage marks where they would matter.",
        ],
        "buyer_checks": [
            "Ask for measurements if you need it to fit a shelf, drawer, table, or cabinet.",
            "Please confirm whether you care more about display condition or everyday function.",
            "This is a good local pickup item because shipping would be more trouble than it is worth.",
        ],
    },
    "Clothing": {
        "features": [
            "The fabric, seams, zipper or buttons, and visible print areas were checked during inspection.",
            "Best for casual outfits, school days, conventions, layered streetwear, or a comfortable weekend look.",
            "It has been stored folded indoors and should be washed or steamed before regular wear.",
            "Fit can vary by brand, so flat measurements are useful when sizing is important.",
        ],
        "buyer_checks": [
            "Message for shoulder, chest, length, waist, or strap measurements before committing.",
            "Please check the stated condition and do not assume retail-new condition unless marked new.",
            "This is better for someone comfortable with local secondhand clothing pickup.",
        ],
    },
    "Books & Media": {
        "features": [
            "I checked the cover, corners, spine, pages, discs, inserts, or sleeves depending on the item.",
            "Good for a reading shelf, art reference stack, collector binder, media corner, or gift bundle.",
            "Pages and packaging may show normal shelf wear, but the item is still useful for reading, display, or collecting.",
            "If the item is a set or lot, the quantity in the listing is the quantity included.",
        ],
        "buyer_checks": [
            "Ask about edition, language, missing inserts, page condition, or exact volume numbers if those details matter.",
            "Please review whether you want it for collecting condition or casual use.",
            "This is a local listing, so I can answer questions before you decide to meet.",
        ],
    },
    "General": {
        "features": [
            "I checked the main usable parts, closures, handles, compartments, and visible surfaces.",
            "Useful for everyday organization, hobby supplies, desk cleanup, commuting, or small-space storage.",
            "It is easy to carry, store, or add into an existing setup without much commitment.",
            "The item has normal local-marketplace handling expectations rather than factory-perfect presentation.",
        ],
        "buyer_checks": [
            "Ask for dimensions, compartment count, weight, or close-up photos if you need exact details.",
            "Please confirm whether the quantity and condition work for your intended use.",
            "This should be a simple pickup item for someone who wants a useful object at a lower local price.",
        ],
    },
}
PICKUP_NOTES = [
    "I can usually coordinate a public meetup around campus, coffee shops, or a busy shopping plaza.",
    "Local pickup is easiest, but timing can be flexible if we confirm in advance.",
    "Please message first so we can agree on a safe public handoff spot.",
    "I prefer clear pickup windows and quick confirmation on the day of meeting.",
    "For business stock, please confirm availability before making a special trip.",
    "For individual trades, payment and delivery stay off-platform and should be agreed in chat.",
]
CONDITION_NOTES = {
    "NEW": "new and unused",
    "LIKE_NEW": "like new with very light handling",
    "GOOD": "in good pre-owned condition",
    "OPEN_BOX": "opened for inspection and kept clean",
    "FAIR": "fair condition with visible handling marks",
}


def rng_for(*parts):
    digest = hashlib.sha256("|".join(str(part) for part in parts).encode("utf-8")).digest()
    return random.Random(int.from_bytes(digest[:8], "big"))


def category_details(category_name, rng):
    bank = CATEGORY_DETAIL_BANK.get(category_name, CATEGORY_DETAIL_BANK["General"])
    return {
        "features": rng.sample(bank["features"], 2),
        "buyer_check": rng.choice(bank["buyer_checks"]),
    }


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
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


def generated_item(index, row):
    listing_id, seller_type, category_name = row[0], row[1], row[2]
    rng = rng_for(listing_id, index, category_name)
    catalog = ITEMS_BY_CATEGORY.get(category_name, ITEMS_BY_CATEGORY["General"])
    item = rng.choice(catalog)
    brand = rng.choice(BRANDS)
    color = rng.choice(COLORS)
    detail = rng.choice(DETAILS)
    model = f"{brand.split()[0].upper()[:3]}-{rng.randrange(120, 998)}{chr(65 + rng.randrange(0, 26))}"
    quantity_word = rng.choice(["single", "bundle", "set", "kit", "lot", "compact pack"])
    if seller_type == "BUSINESS":
        title_templates = [
            "{brand} {item} in {color}, {model}",
            "{color_title} {item} by {brand}",
            "{model} {item} with clean everyday finish",
            "{brand} {item} - practical {color} edition",
            "{brand} {quantity_word} {item} set",
            "{color_title} {item}, {model}",
        ]
    else:
        title_templates = [
            "{brand} {item} in {color}, {model}",
            "{color_title} {item} by {brand} - {detail}",
            "{brand} {quantity_word} {item}, local pickup ready",
            "{model} {item} with clean everyday finish",
            "{brand} {item} - practical {color} edition",
            "{quantity_word_title} of {brand} {item}, {detail}",
            "{brand} {item} for desk, home, or school use",
            "{color_title} {item}, gently handled {model}",
        ]
    title = rng.choice(title_templates).format(
        brand=brand,
        item=item,
        color=color,
        color_title=color.title(),
        detail=detail,
        model=model,
        quantity_word=quantity_word,
        quantity_word_title=quantity_word.title(),
    )
    title = " ".join(title.replace(" set set", " set").replace(" bundle bundle", " bundle").split())
    suffix = "private sale"
    if seller_type == "INDIVIDUAL" and len(title) + len(suffix) + 3 <= 180 and rng.random() < 0.4:
        title = f"{title} - {suffix}"
    return {
        "item": item,
        "brand": brand,
        "color": color,
        "detail": detail,
        "model": model,
        "quantity_word": quantity_word,
        "title": title[:180],
        "rng": rng,
    }


def item_title(index, row):
    return generated_item(index, row)["title"]


def item_description(index, row, item):
    (
        _listing_id,
        seller_type,
        category_name,
        condition_code,
        price_amount,
        currency,
        quantity,
        public_city,
        public_region,
        _original_file_name,
    ) = row
    rng = item["rng"]
    condition_phrase = CONDITION_NOTES.get(condition_code, "ready for a new owner")
    city = public_city or "Irvine"
    region = public_region or "Orange County"
    price_label = f"{float(price_amount):.2f} {currency}"
    selected_uses = ", ".join(rng.sample(USE_CASES, 3))
    selected_condition = " ".join(rng.sample(CONDITION_DETAILS, 2))
    item_details = category_details(category_name, rng)
    if seller_type == "BUSINESS":
        return business_item_description(category_name, condition_phrase, item, item_details, quantity, rng)

    pickup_note = rng.choice(PICKUP_NOTES)
    shared_packing_notes = [
        "I will pack it in a clean bag or small box if we meet in person.",
        "It can be handed off as-is, but I can add simple protective wrapping on request.",
        "The item is already separated from my regular storage so it should be quick to pick up.",
        "I can include the small extras shown with it if they are still available at pickup time.",
    ]
    business_packing_notes = [
        "For store stock, items are checked before handoff and quantity is tracked from the listing record.",
        "Store inventory is staged by listing, so please confirm quantity before pickup if you need more than one.",
    ]
    packing_note = rng.choice(shared_packing_notes + (business_packing_notes if seller_type == "BUSINESS" else []))
    reason_note = rng.choice([
        "Selling because I am clearing shelf space and keeping only the things I use often.",
        "This was part of a larger bundle, and this piece is not needed for my current setup.",
        "I bought a different version, so this one is ready for someone who will actually use it.",
        "It has been sitting unused, and I would rather move it locally than keep storing it.",
        "The item is useful, but it no longer matches the way I organize my space.",
    ])
    buyer_note = rng.choice([
        "Best fit for someone who wants a practical item without paying full retail.",
        "Good choice if you care more about function and appearance than sealed packaging.",
        "Please compare the title, category, price, and quantity before messaging.",
        "Ask for clarification if you need exact measurements or want to confirm small details.",
        "Message ahead if you want a closer look at a specific angle or detail.",
    ])
    seller_policy = (
        "This is an individual listing, so the platform does not handle payment, delivery, refunds, or buyer protection. "
        "Please arrange everything directly and only use a public handoff plan you are comfortable with."
        if seller_type == "INDIVIDUAL"
        else "This is business store inventory, so quantity is listed clearly and the item should remain available unless the store updates it. "
        "Contact the store before making a trip if you need several units at once."
    )
    opening_templates = [
        (
            f"Listing a {item['color']} {item['brand']} {item['item']} marked as {condition_phrase}. "
            f"It is a practical everyday {category_name.lower()} item for {selected_uses}, with details focused on how this item would actually be used."
        ),
        (
            f"This {item['item']} is a {item['brand']} piece with model code {item['model']} and a {item['color']} finish. "
            f"The condition is {condition_phrase}, and it should work well for someone looking for this specific kind of {category_name.lower()} listing."
        ),
        (
            f"Available here is a {item['quantity_word']} {item['item']} from {item['brand']}. "
            f"It fits everyday use around {selected_uses}, and the current condition is {condition_phrase}."
        ),
    ]
    details = [
        rng.choice(opening_templates),
        (
            f"The item is categorized under {category_name}, priced at {price_label}, "
            f"and has a listed quantity of {quantity}. {item['detail'].capitalize()}, with enough information here for a buyer to decide whether to ask follow-up questions."
        ),
        (
            f"{selected_condition} {packing_note} {reason_note}"
        ),
        (
            f"For this {item['item']}, the important practical details are: {item_details['features'][0]} "
            f"{item_details['features'][1]} {item_details['buyer_check']}"
        ),
        (
            f"Pickup or local coordination is centered around {city}, {region}. {pickup_note} "
            f"{buyer_note}"
        ),
        (
            f"{seller_policy} The listing was reviewed with the current title, price, location, and quantity details shown here."
        ),
    ]
    rng.shuffle(details)
    return "\n\n".join(details)


def business_item_description(category_name, condition_phrase, item, item_details, quantity, rng):
    material = rng.choice([
        "a smooth exterior finish",
        "a compact profile",
        "rounded everyday edges",
        "a lightweight body",
        "a sturdy main construction",
        "a simple low-maintenance surface",
        "a clean modern shape",
    ])
    design_note = rng.choice([
        "The design is intentionally practical, with no unnecessary ornamentation.",
        "The proportions make it easy to place on a shelf, desk, counter, or in a bag.",
        "The finish is neutral enough to blend into a casual home, school, or office setup.",
        "The piece is meant for regular handling rather than display-only storage.",
        "The layout favors easy access, simple cleaning, and everyday durability.",
    ])
    contents_note = rng.choice([
        f"The available product count is {quantity} unit{'s' if str(quantity) != '1' else ''} of the same item style.",
        f"The item is treated as a {item['quantity_word']} entry with consistent item details across the listed quantity.",
        "Included components are described by the item name and category; no extra accessories are implied unless separately specified.",
        "The item can be used as a standalone piece or paired with similar everyday accessories.",
    ])
    category_specific = " ".join(item_details["features"])
    condition_note = rng.choice([
        f"The catalog condition is {condition_phrase}; visible surfaces should be interpreted with normal handling expectations.",
        f"Condition is listed as {condition_phrase}, with the item copy focused on function, finish, and expected use.",
        f"The item is marked {condition_phrase}, so the main expectation is a usable product with the stated condition level.",
    ])
    care_note = rng.choice([
        "Wipe gently with a dry or slightly damp cloth when needed.",
        "Keep it away from unnecessary moisture, heat, or pressure during storage.",
        "Store it flat or upright depending on the item shape to preserve the finish.",
        "Use normal care for this category and avoid forcing closures, seams, ports, or corners.",
    ])
    paragraphs = [
        (
            f"{item['brand']} {item['item']} is a {item['color']} {category_name.lower()} product with model code {item['model']}. "
            f"It has {material} and is best suited for {', '.join(rng.sample(USE_CASES, 2))}."
        ),
        (
            f"{category_specific} {design_note}"
        ),
        (
            f"{condition_note} {contents_note}"
        ),
        (
            f"{care_note} The overall product profile is simple, useful, and easy to understand from its title, category, condition, and quantity."
        ),
    ]
    rng.shuffle(paragraphs)
    return "\n\n".join(paragraphs)


def main():
    rows = mysql_query("""
        select l.id, l.seller_type, c.name, l.condition_code, l.price_amount, l.currency,
               l.quantity, l.public_city, l.public_region, mo.original_file_name
        from catalog.listings l
        join catalog.categories c on c.id = l.category_id
        join catalog.listing_media_objects mo on mo.listing_id = l.id
        where mo.object_key like 'seed/taihou/%'
        group by l.id, l.seller_type, c.name, l.condition_code, l.price_amount, l.currency,
                 l.quantity, l.public_city, l.public_region, mo.original_file_name
        order by l.seller_type, l.id
    """)
    if not rows:
        print("No Taihou seeded listings found.")
        return

    now = dt.datetime.now(dt.UTC).strftime("%Y-%m-%d %H:%M:%S.000000")
    statements = ["SET NAMES utf8mb4;", "START TRANSACTION;"]
    seen_titles = set()
    for index, row in enumerate(rows, start=1):
        listing_id = row[0]
        item = generated_item(index, row)
        title = item["title"]
        if title in seen_titles:
            title = f"{title[:168]} ({listing_id[-6:]})"
            if title in seen_titles:
                raise RuntimeError(f"Generated duplicate title: {title}")
        seen_titles.add(title)
        description = item_description(index, row, item)
        condition_notes = (
            f"{CONDITION_NOTES.get(row[3], 'Ready for review').capitalize()}. "
            f"Seller notes: {item['detail']}; please ask if you need exact measurements."
        )
        statements.append(f"""
            update catalog.listings
            set title = {sql_string(title)},
                description = {sql_string(description)},
                condition_notes = {sql_string(condition_notes)},
                updated_at = {sql_string(now)}
            where id = {sql_string(listing_id)};
        """)
        statements.append(f"""
            update catalog.listing_images
            set alt_text = {sql_string(title)},
                updated_at = {sql_string(now)}
            where listing_id = {sql_string(listing_id)};
        """)
    statements.append("COMMIT;")
    mysql_exec("\n".join(statements))
    print(f"Updated {len(rows)} seeded listings with real item titles and long descriptions.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"Copy update failed: {exc}", file=sys.stderr)
        sys.exit(1)
