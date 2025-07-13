# 🧠 Multilingual Document Analyzer for Tanzu Platform  
### Powered by Spring AI

![PDF Analyzer](screenshot.jpg)

This is a Spring Boot demo app that lets you upload a collection of PDF and Word documents and ask questions about them using your preferred embedding model and LLM.

---

## ✨ Features

- **Multilingual Support**: Fully supports both **English** and **Hebrew**.
- **Automatic Language Detection**: Right-to-left (RTL) documents like Hebrew PDFs are automatically detected and rendered with proper layout.  
- **Word Documents**: Microsoft Word files are handled seamlessly regardless of text direction.
- **Cross-Language Q&A**: Ask questions in **either language**. Responses are returned in the **current UI language**, independent of the source document's language.
- **Embedding Storage**: Uses **pgVector** (PostgreSQL) to store document embeddings.
- **Conversation Context**: Includes a conversation ID so that Spring AI can maintain chat history per user. Use a unique ID to avoid clashing with other sessions.
- **UI Example**: The screenshot above shows the same question asked twice—first in English, then in Hebrew. Same RAG, different language. Magic!

---

## 🚀 Running Locally

Make sure you have **Ollama** installed.

1. **Start a local PostgreSQL + pgVector container:**

   ```bash
   docker run --name pgvector \
     -e POSTGRES_USER=myuser \
     -e POSTGRES_PASSWORD=mypassword \
     -e POSTGRES_DB=mydb \
     -p 5432:5432 \
     -d ankane/pgvector:latest

2. **Run the application:**

```bash
mvn spring-boot:run
```

⚠️ On first run, Spring AI will download required models. You can customize the embedding and chat models in application.yaml.

### ☁️ Deploying to Tanzu Platform (Cloud Foundry)
Provision these 3 marketplace services:

- `embed` – A GenAI plan that supports embeddings.
- `chat` – A GenAI plan that supports chat completion.
- `postgres` – A PostgreSQL database.

Then deploy with:

```
./mvnw clean package && cf push
```

Pull requests are welcomed!

Oded S.
