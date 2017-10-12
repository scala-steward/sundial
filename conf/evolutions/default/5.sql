# --- !Ups

ALTER TABLE batch_service_state ADD COLUMN logstream_name VARCHAR(255);

# --- !Downs

ALTER TABLE batch_service_state DROP COLUMN logstream_name;