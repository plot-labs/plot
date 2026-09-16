alter table chat_response_versions
  add column response_text text;

alter table chat_response_versions
  add constraint chat_response_versions_response_text_length
  check (response_text is null or (char_length(trim(response_text)) between 1 and 40000));
