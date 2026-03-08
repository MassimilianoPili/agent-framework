-- G1: Conversation History — persist full LLM conversation for post-mortem debugging
ALTER TABLE dispatch_attempts ADD COLUMN conversation_log JSONB;
