# 🧠 Multilingual Document Analyzer Powered by Spring AI

![PDF Analyzer (English)](Screenshot-eng.png)
![PDF Analyzer (Hebrew)](Screenshot-heb.png)

This is a Spring AI demo app that lets you upload a collection of PDF and Word documents and ask questions about them using your preferred embedding model and LLM.

---

## ✨ Features

- **Multilingual Support**: Fully supports both **English** and **Hebrew**.
- **Automatic Language Detection**: Right-to-left (RTL) documents like Hebrew PDFs are automatically detected and rendered with proper layout.  
- **Word Documents**: Microsoft Word files are handled seamlessly regardless of text direction.
- **Cross-Language Q&A**: Ask questions in **either language**. Responses are returned in the **current UI language**, independent of the source document's language.
- **Embedding Storage**: Uses **pgVector** (PostgreSQL) to store document embeddings.
- **Conversation History**: Includes a conversation history for all previous chats. Each conversation has its own chat memory for context.
- **Single Sign-On**: Supports Github OIDC, or Pivotal SSO while running on Tanzu Platform for Cloud Foundry. Documents and conversation history is managed on a per-user basis.

---

## 🚀 Running Locally

Make sure you have **Ollama** and Docker installed and running.

1. **Start a local PostgreSQL + pgVector container:**

   ```bash
   docker run --name pgvector \
     -e POSTGRES_USER=myuser \
     -e POSTGRES_PASSWORD=mypassword \
     -e POSTGRES_DB=mydb \
     -p 5432:5432 \
     -d ankane/pgvector:latest
    ```
2. Create a Github OAuth app at https://github.com/settings/developers.

3. Set environment variables:

```
export GITHUB_CLIENT_ID=<client id>
export GITHUB_CLIENT_SECRET=<client secret>
```
4. Run the application with the github Spring profile:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=github"
```

⚠️ On first run, Spring AI will download required models. You can customize the embedding and chat models in `application.yaml`.

### ☁️ Deploying to Tanzu Platform (Cloud Foundry)
Provision these 4 marketplace services:

- `embed` – A GenAI plan that supports embeddings.
- `chat` – A GenAI plan that supports chat completion.
- `postgres` – A PostgreSQL database.
- `sso` - A Pivotal SSO Service.

Then deploy with:

```
./mvnw clean package && cf push
```

Pull requests are welcomed!

@odedia
odedia.org