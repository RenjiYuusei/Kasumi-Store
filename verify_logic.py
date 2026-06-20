import re

PLACE_URL_REGEX = re.compile(r"(?i)placeid=([0-9]{3,16})")
GID_LOG_REGEX = re.compile(r"(?i)gameinstanceid\"?\s*[=:]?\s*\"?([0-9a-fA-F]{8}-[0-9a-fA-F-]{4,55})")
ACCESS_CODE_REGEX = re.compile(r"(?i)accesscode\"?\s*[=:]?\s*\"?([0-9a-fA-F]{8}-[0-9a-fA-F-]{4,55})")

log_lines = [
    "1781956371.293 10111 53246 53246 I rbx.web : [c$k.d()-19]: -> https://www.roblox.com/vi/games/start?placeId=140409475718339&accessCode=b66afff7-708c-4d01-8d59-0c493d096dd3&joinAttemptId=4a92c40b-f585-4d57-92d8-ab2565746ec1&joinAttemptOrigin=privateServerListJoin",
    "1781956372.000 10111 53246 53246 D Roblox : Checking placeId=140409475718339"
]

placeId = None
gid = None
accessCode = None

# Logic simulation
# 1. Find placeId (latest)
for line in reversed(log_lines):
    match = PLACE_URL_REGEX.search(line)
    if match:
        placeId = match.group(1)
        break

print(f"Detected PlaceId: {placeId}")

if placeId:
    bestLine = None
    # 2. Find best metadata line for this placeId
    for line in reversed(log_lines):
        match = PLACE_URL_REGEX.search(line)
        if match and match.group(1) == placeId:
            if ACCESS_CODE_REGEX.search(line) or "rbx.web" in line.lower():
                bestLine = line
                break

    if not bestLine:
        for line in reversed(log_lines):
            match = PLACE_URL_REGEX.search(line)
            if match and match.group(1) == placeId:
                bestLine = line
                break

    print(f"Best Line found: {bestLine is not None}")
    if bestLine:
        gid_match = GID_LOG_REGEX.search(bestLine)
        if gid_match:
            gid = gid_match.group(1)

        access_match = ACCESS_CODE_REGEX.search(bestLine)
        if access_match:
            accessCode = access_match.group(1)

print(f"Final GID: {gid}")
print(f"Final AccessCode: {accessCode}")
isPrivate = accessCode is not None
print(f"Is Private Server: {isPrivate}")
