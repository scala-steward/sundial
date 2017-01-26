# --- !Ups
ALTER TABLE process_definition ADD COLUMN notifications JSONB NOT NULL DEFAULT '[]'::jsonb;

# --- !Downs
ALTER TABLE process_definition DROP COLUMN IF EXISTS notifications;

