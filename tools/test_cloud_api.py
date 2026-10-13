#!/usr/bin/env python3
"""Smoke test real disposable local Supabase Auth/PostgREST. Creates temporary users.

This intentionally accepts only loopback URLs. It never prints passwords or JWTs.
Use alongside the SQL pgTAP suite; it does not replace encryption/Android tests.
"""
import argparse
import base64
import json
import secrets
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--auth-url', default='http://127.0.0.1:54321/auth/v1')
    parser.add_argument('--rest-url', default='http://127.0.0.1:54321/rest/v1')
    parser.add_argument('--publishable-key', default='')
    args = parser.parse_args()
    for value in (args.auth_url, args.rest_url):
        parsed = urllib.parse.urlparse(value)
        if parsed.hostname not in ('localhost', '127.0.0.1', '::1') or parsed.username or parsed.password:
            parser.error('This test must use an explicitly disposable local stack on loopback.')
    count = 0

    def call(origin, path, token=None, body=None, expected=200):
        headers = {'Content-Type': 'application/json'}
        if args.publishable_key:
            headers['apikey'] = args.publishable_key
        if token:
            headers['Authorization'] = 'Bearer ' + token
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(origin.rstrip('/') + '/' + path, data=data, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                status, text = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, text = error.code, error.read()
        assert status == expected, f'{path.split("?")[0]}: expected HTTP {expected}, got {status}'
        return json.loads(text) if text else None

    def check(condition):
        nonlocal count
        assert condition
        count += 1

    suffix = uuid.uuid4().hex
    password = secrets.token_urlsafe(32)
    email = 'opentv-test-' + suffix + '@example.test'
    own = call(args.auth_url, 'signup', body={'email': email, 'password': password})
    other = call(args.auth_url, 'signup', body={'email': 'opentv-other-' + suffix + '@example.test', 'password': secrets.token_urlsafe(32)})
    second = call(args.auth_url, 'token?grant_type=password', body={'email': email, 'password': password})
    check(own['user']['id'] == second['user']['id'] != other['user']['id'])
    first_device, second_device, other_device = [str(uuid.uuid4()) for _ in range(3)]

    def rpc(name, account, body, expected=200):
        return call(args.rest_url, 'rpc/' + name, account['access_token'], body, expected)

    for account, device in ((own, first_device), (second, second_device), (other, other_device)):
        rpc('sync_register_device', account, {'device': device, 'nickname': 'Disposable API test'}, expected=204)
    commitment = base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip('=')
    rpc('sync_create_keyring', own, {'commitment': commitment}, expected=204)
    record = dict(table_name='sync_providers', id=str(uuid.uuid4()), clock=100, writer_device_id=first_device,
                  deleted=False, ciphertext='1.' + secrets.token_urlsafe(12) + '.' + secrets.token_urlsafe(64), schema_version=1)
    winner = rpc('sync_push', own, {'device': first_device, 'records': [record]})[0]
    check(winner['id'] == record['id'])
    check(len(rpc('sync_pull', second, {'device': second_device, 'after_sequence': 0})) == 1)
    check(call(args.rest_url, 'sync_providers?select=id', other['access_token']) == [])
    check(call(args.rest_url, 'sync_keyring?select=commitment', other['access_token']) == [])
    rpc('sync_pull', other, {'device': first_device, 'after_sequence': 0}, expected=403)
    check(True)
    record['clock'], record['deleted'] = 200, True
    rpc('sync_push', own, {'device': first_device, 'records': [record]})
    record['clock'], record['deleted'] = 100, False
    check(rpc('sync_push', own, {'device': first_device, 'records': [record]})[0]['deleted'])
    rpc('sync_unlink_device', own, {'device': second_device}, expected=204)
    rpc('sync_pull', second, {'device': second_device, 'after_sequence': 0}, expected=403)
    check(call(args.rest_url, 'sync_providers?select=id', second['access_token']) == [])
    refreshed = call(args.auth_url, 'token?grant_type=refresh_token', body={'refresh_token': own['refresh_token']})
    check('refresh_token' in refreshed and refreshed['user']['id'] == own['user']['id'])
    rpc('sync_authorize_device', refreshed, {'device': first_device}, expected=204)
    check(True)
    call(args.auth_url, 'logout?scope=local', refreshed['access_token'], body={}, expected=204)
    rpc('sync_pull', refreshed, {'device': first_device, 'after_sequence': 0}, expected=403)
    check(True)
    # Accounts are intentionally confined to the disposable local database. Stop/reset that stack
    # after the test; account deletion requires an admin API key which this script never accepts.
    print(f'{count} real Auth/PostgREST checks passed (temporary users remain in the disposable stack).')


if __name__ == '__main__':
    main()
