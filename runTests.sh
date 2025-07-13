#!/bin/bash

URL="http://localhost:8080/document/query"
OUTPUT_FILE="test-results/results.txt"
echo "" > "$OUTPUT_FILE"

QUESTIONS=(
  "על מה המסמכים?"
  "מה היו דרישותיו של נאצר?"
  "מה חשב בן גוריון על משה שרת?"
  "מה ניתן להסיק לגבי יחסי ישראל ומצרים גם בימינו אלו?"
  "מה היו הישגיה של ישראל במלחמת העצמאות?"
  "האם ניתן לכנס את מליאת הכנסת במהלך הפגרה?"
  "האם יור הכנסת  צריך להיוועץ בנשיא המדינה?"
  "מהם כללי האתיקה שחלים על חברי כנסת?"
  "האם חבר כנסת רשאי לתרום לעמותה?"
  "מה קורה אם אני בחו״ל ואני צריך אשפוז בבית חולים?"
)

for QUESTION in "${QUESTIONS[@]}"
do
  echo "=== QUESTION ==="
  echo "$QUESTION"

RAW_RESPONSE=$(http POST "$URL" \
  Content-Type:text/plain \
  X-Chat-Language:he \
  X-Conversation-Id:2345245 \
  <<< "$QUESTION" \
  --print=b)

  echo "=== ANSWER ==="
  echo "$RAW_RESPONSE"

  {
    echo "=== QUESTION ==="
    echo "$QUESTION"
    echo "=== ANSWER ==="
    echo "$RAW_RESPONSE"
    echo ""
  } >> "$OUTPUT_FILE"

done
