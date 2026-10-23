-- TexJAHC · RLS automático (pendiente de autorización: NO se ha aplicado)
-- Toda tabla nueva en "public" nace con Row Level Security activado.
-- Sin políticas, nadie puede leerla ni escribirla desde la API pública (anon / authenticated)
-- hasta que se escriba una política. Las tablas que ya existen no cambian.
-- Aplicar: Supabase › SQL Editor › pegar y ejecutar (o pedírselo a Claude).
create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

create or replace function private.rls_auto_enable()
returns event_trigger
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
  cmd record;
begin
  for cmd in
    select * from pg_event_trigger_ddl_commands()
    where command_tag in ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
      and object_type in ('table', 'partitioned table')
  loop
    if cmd.schema_name = 'public' then
      execute format('alter table if exists %s enable row level security', cmd.object_identity);
    end if;
  end loop;
end;
$$;

revoke all on function private.rls_auto_enable() from public, anon, authenticated;

drop event trigger if exists ensure_rls;
create event trigger ensure_rls
  on ddl_command_end
  when tag in ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
  execute function private.rls_auto_enable();
