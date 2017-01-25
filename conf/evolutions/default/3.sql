# --- !Ups
ALTER TABLE process_definition ADD COLUMN notifications JSONB NOT NULL;

# --- !Downs
ALTER TABLE process_definition DROP COLUMN IF EXISTS notifications;

