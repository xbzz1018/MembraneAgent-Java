CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE mb_vectors (
 evidence_id VARCHAR(96) PRIMARY KEY REFERENCES mb_evidence(id) ON DELETE CASCADE,
 namespace VARCHAR(128) NOT NULL, embedding vector(${embeddingDimensions}) NOT NULL
);
CREATE INDEX mb_vectors_cosine ON mb_vectors USING hnsw(embedding vector_cosine_ops);
