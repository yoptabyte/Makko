ALTER TABLE links
  ADD COLUMN export_directory VARCHAR(500) NULL AFTER collection_id,
  ADD COLUMN export_file_name VARCHAR(255) NULL AFTER export_directory;
