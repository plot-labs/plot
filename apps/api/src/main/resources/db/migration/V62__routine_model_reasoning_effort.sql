alter table routines
  add column model varchar(100) not null default 'auto',
  add column reasoning_effort varchar(16);

alter table routines
  add constraint routines_model_not_blank
    check (length(trim(model)) > 0),
  add constraint routines_reasoning_effort_check
    check (reasoning_effort is null or reasoning_effort in
      ('none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'));
