-- Rollback for 0005_customers_payroll_hirepurchase.sql

drop view if exists hire_purchase_overview;
drop view if exists customer_purchase_summary;

drop table if exists hire_purchase_payments;
drop table if exists hire_purchase_contracts;
drop table if exists payroll_deductions;
drop table if exists customers;

alter table sales drop column if exists staff_number;
alter table sales drop column if exists payment_channel;
alter table sales drop column if exists customer_id;

alter table products drop column if exists thumbnail_url;
alter table products drop column if exists image_url;
alter table products drop column if exists description;
