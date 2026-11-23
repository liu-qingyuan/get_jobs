"""Independent loopback receiver for the intercepted browser's fake platform traffic."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from threading import Thread
from urllib.parse import parse_qs, urlsplit

PREFIX = '/__fixture_delivery__/'


def text_sync_frame(client_id, message_id):
    def varint(value):
        raw = bytearray()
        while value >= 128:
            raw.append((value & 127) | 128)
            value >>= 7
        raw.append(value)
        return raw

    mid = varint(message_id)
    sync = b'\x08' + varint(client_id) + b'\x10' + mid + b'\x18' + mid
    return b'\x08\x04\x10\x01\x2a' + varint(len(sync)) + sync


class DeliveryReceiver:
    def __init__(self, job_id, text, approved_file):
        self.scope = {'jobId': job_id, 'owner': 'own-1', 'peer': 'peer-1'}
        self.ref = {'resumeId': 'approved-id', 'originalOssKey': 'approved-key', 'version': 'receiver-revision-1'}
        self.asset = (self.ref.copy(), bytes(approved_file))
        self.text = text
        self.reset()
        receiver = self

        class Handler(BaseHTTPRequestHandler):
            def reply(self, code, value, content_type='application/json'):
                body = json.dumps(value).encode() if content_type == 'application/json' else value
                self.send_response(code)
                self.send_header('Content-Type', content_type)
                self.send_header('Content-Length', str(len(body)))
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                kind = self.path.removeprefix(PREFIX)
                if kind not in ('text', 'resume'):
                    return self.reply(404, {})
                payload = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                receiver.requests.append((kind, payload))  # Count even a rejected wrong dispatch.
                receiver.trace.append(('request', kind))
                ack = receiver.accept(kind, payload)
                if not ack:
                    return self.reply(422, None)
                receiver.trace.append(('ack', kind, ack['messageId']))
                if kind == 'text':
                    return self.reply(200, text_sync_frame(payload['clientMessageId'], ack['messageId']),
                                      'application/octet-stream')
                return self.reply(200, ack)

            def do_GET(self):
                url = urlsplit(self.path)
                query = parse_qs(url.query)
                if url.path == PREFIX + 'asset' and query == {
                    'resumeId': [receiver.asset[0]['resumeId']], 'version': [receiver.asset[0]['version']]
                }:
                    receiver.trace.append(('asset', query['version'][0]))
                    return self.reply(200, receiver.asset[1], 'application/octet-stream')
                if url.path == PREFIX + 'history' and query == {'peer': [receiver.scope['peer']]}:
                    rows = [r for r in receiver.history if r['mediaType'] != receiver.hidden_media_type]
                    receiver.trace.append(('history', tuple(r['messageId'] for r in rows)))
                    return self.reply(200, {'hasMore': False, 'pageSize': 30, 'resultList': rows})
                return self.reply(404, {})

            def log_message(self, *args):
                pass

        self.server = HTTPServer(('127.0.0.1', 0), Handler)
        self.url = f'http://127.0.0.1:{self.server.server_port}'
        self.thread = Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def reset(self, *, hidden_media_type=None):
        self.requests = []
        self.trace = []
        self.history = []
        self.delivered_bytes = []
        self.hidden_media_type = hidden_media_type

    def accept(self, kind, payload):
        if kind == 'text':
            client_id = payload.get('clientMessageId')
            if (type(client_id) is not int or not 0 < client_id < 2**53
                    or payload != {**self.scope, 'text': self.text, 'clientMessageId': client_id} or self.history):
                return None
            media_type = 1
            body = {'text': {'content': payload['text']}}
            ack = {}
        else:
            ref, content = self.asset
            if payload != {**self.scope, 'resume': ref} or [r['mediaType'] for r in self.history] != [1]:
                return None
            self.delivered_bytes.append(content)
            media_type = 6
            body = {'compressiveCard': {'type': 10, 'content': json.dumps(ref)}}
            ack = {'resume': ref}
        message_id = 4201 + len(self.history)
        row = {'messageId': message_id, 'seq': message_id, 'addTime': message_id,
               'fromSn': self.scope['owner'], 'toSn': self.scope['peer'],
               'mediaType': media_type, 'status': 0, 'body': body}
        self.history.insert(0, row)
        if kind == 'text':
            row['clientMessageId'] = client_id
        return {'messageId': message_id, **ack}

    def close(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()
