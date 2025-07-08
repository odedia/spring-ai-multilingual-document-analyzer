from flask import Flask, request, jsonify, render_template_string
from io import BytesIO
from pdfminer.high_level import extract_text
import re

app = Flask(__name__)

def fix_hebrew_text(text):
    """
    Reverses Hebrew lines that have character-order flipped.
    This happens when PDFs store Hebrew in visual LTR layout.
    """
    lines = text.splitlines()
    fixed_lines = []

    for line in lines:
        stripped = line.strip()
        if not stripped:
            fixed_lines.append("")  # preserve blank lines for paragraph separation
            continue

        # Count Hebrew characters
        hebrew_chars = sum(1 for c in stripped if '\u0590' <= c <= '\u05FF')

        if hebrew_chars > len(stripped) * 0.3:
            # Reverse the whole line (characters are in wrong order)
            stripped = stripped[::-1]

        fixed_lines.append(stripped)

    return "\n".join(fixed_lines)

@app.route("/", methods=["GET"])
def index():
    return render_template_string("""
    <!doctype html>
    <title>Upload PDF</title>
    <h1>Upload a PDF file</h1>
    <form method=post enctype=multipart/form-data action="/extract">
      <input type=file name=file>
      <input type=submit value="Upload and Extract">
    </form>
    """)

@app.route("/extract", methods=["POST"])
def extract():
    uploaded = request.files.get("file")
    if not uploaded or not uploaded.filename.lower().endswith(".pdf"):
        return jsonify({"error": "Please upload a PDF file"}), 400

    pdf_bytes = uploaded.read()
    raw_text = extract_text(BytesIO(pdf_bytes))

    # Fix reversed Hebrew text
    fixed_text = fix_hebrew_text(raw_text)

    # Split into paragraphs using blank lines
    paragraphs = [p.strip() for p in re.split(r'\n\s*\n', fixed_text) if p.strip()]

    return jsonify(paragraphs)

if __name__ == "__main__":
    app.run(debug=True, port=5000)
