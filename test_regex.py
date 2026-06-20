import re

PLACE_URL_REGEX = re.compile(r"(?i)placeid=([0-9]{3,16})")
GID_LOG_REGEX = re.compile(r"(?i)gameinstanceid\"?\s*[=:]?\s*\"?([0-9a-fA-F]{8}-[0-9a-fA-F-]{4,55})")
ACCESS_CODE_REGEX = re.compile(r"(?i)accesscode\"?\s*[=:]?\s*\"?([0-9a-fA-F]{8}-[0-9a-fA-F-]{4,55})")

log_line = "1781956371.293 10111 53246 53246 I rbx.web : [c.d()-19]: -> https://www.roblox.com/vi/games/start?placeId=140409475718339&accessCode=b66afff7-708c-4d01-8d59-0c493d096dd3&joinAttemptId=4a92c40b-f585-4d57-92d8-ab2565746ec1&joinAttemptOrigin=privateServerListJoin"

place_match = PLACE_URL_REGEX.search(log_line)
access_match = ACCESS_CODE_REGEX.search(log_line)

print(f"PlaceId: {place_match.group(1) if place_match else 'None'}")
print(f"AccessCode: {access_match.group(1) if access_match else 'None'}")
