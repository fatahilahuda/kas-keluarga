-- Muarakas Wear OS: menyambungkan jam dengan kode 6 digit.
-- Jalankan sekali di Supabase (proyek kas-keluarga) → SQL Editor.
--
-- Alur: jam membuat PIN 6 digit + rahasia acak (kas_watch_new) dan menampilkan PIN-nya.
-- HP mengetik PIN itu (kas_watch_link) dan menitipkan kode keluarga + pemilik jam.
-- Jam mengambilnya sekali dengan PIN + rahasia (kas_watch_claim); barisnya langsung dihapus.
-- Tanpa rahasia, menebak PIN tidak memberi apa pun.

create table if not exists public.kas_watch_pair(
  pin text primary key,
  secret_hash text not null,
  code text,
  me text,
  created_at timestamptz not null default now()
);
alter table public.kas_watch_pair enable row level security;
revoke all on public.kas_watch_pair from anon, authenticated;

create or replace function public.kas_watch_new(p_pin text, p_secret text)
returns boolean language plpgsql security definer set search_path to '' as $$
begin
  delete from public.kas_watch_pair where created_at < now() - interval '15 minutes';
  if coalesce(p_pin,'') !~ '^[0-9]{6}$' then raise exception 'bad pin'; end if;
  if length(regexp_replace(coalesce(p_secret,''),'[^A-Za-z0-9]','','g')) < 24 then raise exception 'bad secret'; end if;
  insert into public.kas_watch_pair(pin, secret_hash) values (p_pin, public.kas_hash(p_secret))
    on conflict (pin) do nothing;
  return found;
end $$;

create or replace function public.kas_watch_link(p_pin text, p_code text, p_me text)
returns boolean language plpgsql security definer set search_path to '' as $$
begin
  if public.kas_family_id(p_code) is null then raise exception 'family not found'; end if;
  update public.kas_watch_pair
     set code = upper(regexp_replace(p_code,'[^A-Za-z0-9]','','g')), me = left(coalesce(p_me,''),40)
   where pin = p_pin and code is null and created_at > now() - interval '10 minutes';
  return found;
end $$;

create or replace function public.kas_watch_claim(p_pin text, p_secret text)
returns table(code text, me text) language plpgsql security definer set search_path to '' as $$
begin
  return query
    with d as (
      delete from public.kas_watch_pair w
       where w.pin = p_pin and w.secret_hash = public.kas_hash(p_secret) and w.code is not null
      returning w.code as c, w.me as m)
    select d.c, d.m from d;
end $$;

revoke all on function public.kas_watch_new(text,text), public.kas_watch_link(text,text,text), public.kas_watch_claim(text,text) from public;
grant execute on function public.kas_watch_new(text,text), public.kas_watch_link(text,text,text), public.kas_watch_claim(text,text) to anon, authenticated;
