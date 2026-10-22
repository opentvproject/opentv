#!/usr/bin/env python3
"""
A tiny catch-up laboratory for OpenTV.

Serves three things on one port:

  /playlist.m3u   an M3U playlist whose channels carry every catch-up flavour OpenTV
                  understands — default, append, shift, flussonic, and one with no
                  catch-up at all — plus catchup-days and catchup-correction
  /guide.xml      an XMLTV guide for those channels, with programmes that already
                  finished, so the "Earlier — watch from start" list has something in it
  /catchup/...    the archive endpoint: it logs the exact URL the app asked for, which
                  is the only way to see whether the start time, the duration and the
                  timezone came out right

Every request is printed to the console, so running this while testing shows exactly
what the app asked the provider for.

    python3 tools/catchup-lab/server.py [--port 8099] [--host 10.0.2.2]

--host is the address the *app* should use to reach this server. On the Android
emulator that is 10.0.2.2; on a real phone on the same wifi it is the machine's LAN
address (192.168.x.x).
"""

import argparse
import datetime
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# How long each programme lasts, and how many of them to put in the guide.
SLOT_MINUTES = 60
PAST_SLOTS = 8
FUTURE_SLOTS = 4

# The channels and the catch-up flavour each one demonstrates. `source` is the
# catchup-source template; the server substitutes {host} for itself.
CHANNELS = [
    {
        "id": "lab.default",
        "name": "Lab Default",
        "catchup": "default",
        "source": "{host}/catchup/default.ts?utc={utc}&lutc={lutc}&duration={duration:60}",
        "days": "3",
    },
    {
        "id": "lab.append",
        "name": "Lab Append",
        "catchup": "append",
        "source": "&utc={utc}&lutc={lutc}",
        "days": "7",
    },
    {
        "id": "lab.shift",
        "name": "Lab Shift",
        "catchup": "shift",
        "source": "",
        "days": "",
    },
    {
        "id": "lab.flussonic",
        "name": "Lab Flussonic",
        "catchup": "flussonic",
        "source": "{host}/catchup/flussonic-{Y}-{m}-{d}-{H}-{M}.ts",
        "days": "",
    },
    {
        "id": "lab.none",
        "name": "Lab No Catch-up",
        "catchup": "",
        "source": "",
        "days": "",
    },
]


def playlist(host: str) -> str:
    """The M3U playlist, with the catch-up tags the app reads."""
    lines = [
        "#EXTM3U url-tvg=\"\"",
        # Playlist-wide defaults: a mode and a correction, for the channels that do not
        # say otherwise. The correction is in hours, the way Kodi expects it.
        "#EXTM3U catchup=\"default\" catchup-correction=\"-3.0\"",
    ]
    for channel in CHANNELS:
        attributes = [f'tvg-id="{channel["id"]}"', f'tvg-name="{channel["name"]}"', 'group-title="Lab"']
        if channel["catchup"]:
            attributes.append(f'catchup="{channel["catchup"]}"')
        if channel["source"]:
            attributes.append('catchup-source="%s"' % channel["source"].replace("{host}", host))
        if channel["days"]:
            attributes.append(f'catchup-days="{channel["days"]}"')
        lines.append("#EXTINF:-1 %s,%s" % (" ".join(attributes), channel["name"]))
        lines.append("%s/stream/%s.ts" % (host, channel["id"]))
    # Two channels that actually play, so the player itself can be tried without a provider.
    lines.append('#EXTINF:-1 tvg-id="pub.hls" tvg-name="Public HLS" group-title="Public",Public HLS')
    lines.append("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
    lines.append('#EXTINF:-1 tvg-id="pub.hls.live" tvg-name="Public HLS live" group-title="Public",Public HLS live')
    lines.append("https://test-streams.mux.dev/pts_shift/master.m3u8")
    return "\n".join(lines) + "\n"


def guide(now: datetime.datetime) -> str:
    """An XMLTV guide with programmes that already finished, and some still to come."""
    channels = "".join(
        '  <channel id="%s">\n    <display-name>%s</display-name>\n  </channel>\n'
        % (channel["id"], channel["name"])
        for channel in CHANNELS
    )
    programmes = []
    for channel in CHANNELS:
        # The window is centred on now: a few finished slots, then the current one, then
        # a few upcoming ones. The finished ones are what the catch-up list offers.
        for offset in range(-PAST_SLOTS, FUTURE_SLOTS):
            start = (now - datetime.timedelta(minutes=SLOT_MINUTES * offset)).replace(second=0, microsecond=0)
            end = start + datetime.timedelta(minutes=SLOT_MINUTES)
            title = "%s %+dh" % (channel["name"], -offset)
            programmes.append(
                '  <programme start="%s" stop="%s" channel="%s">\n'
                "    <title>%s</title>\n"
                "    <desc>Lab programme</desc>\n"
                "  </programme>\n"
                % (start.strftime("%Y%m%d%H%M%S +0000"), end.strftime("%Y%m%d%H%M%S +0000"), channel["id"], title)
            )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        "<tv>\n" + channels + "".join(programmes) + "</tv>\n"
    )


def live_playlist() -> str:
    """A playlist of streams that actually play, for trying the player itself.

    The catch-up playlist above points at fake streams so the catch-up URL can be read in
    the log; this one points at public test streams so there is something to watch.
    """
    return "\n".join(
        [
            "#EXTM3U",
            "#EXTINF:-1 tvg-id=\"pub.hls\" tvg-name=\"Public HLS\" group-title=\"Public\",Public HLS",
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            "#EXTINF:-1 tvg-id=\"pub.hls.live\" tvg-name=\"Public HLS live\" group-title=\"Public\",Public HLS live",
            "https://test-streams.mux.dev/pts_shift/master.m3u8",
        ],
    ) + "\n"


class Handler(BaseHTTPRequestHandler):
    host = "127.0.0.1:8099"

    def log_message(self, fmt, *args):  # noqa: A003 - BaseHTTPRequestHandler's own name
        sys.stdout.write("%s - %s\n" % (self.address_string(), fmt % args))
        sys.stdout.flush()

    def do_GET(self):  # noqa: N802 - the name BaseHTTPRequestHandler expects
        now = datetime.datetime.now(datetime.timezone.utc)
        if self.path.startswith("/playlist.m3u"):
            body = playlist(self.host).encode()
            self.send_response(200)
            self.send_header("Content-Type", "audio/x-mpegurl; charset=utf-8")
        elif self.path.startswith("/live.m3u"):
            body = live_playlist().encode()
            self.send_response(200)
            self.send_header("Content-Type", "audio/x-mpegurl; charset=utf-8")
        elif self.path.startswith("/guide.xml"):
            body = guide(now).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/xml; charset=utf-8")
        elif self.path.startswith("/catchup/"):
            # The interesting one: this is the URL the app built for the archive.
            print("\n>>> CATCH-UP REQUEST: %s\n" % self.path, flush=True)
            body = b"not a real stream, but the URL above is the point"
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
        else:
            body = b"not a real stream"
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument(
        "--host",
        default="10.0.2.2",
        help="the address the app should use to reach this server (10.0.2.2 on the emulator)",
    )
    args = parser.parse_args()

    Handler.host = "http://%s:%d" % (args.host, args.port)
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print("Catch-up lab on port %d" % args.port, flush=True)
    print("  playlist: http://%s/playlist.m3u" % Handler.host, flush=True)
    print("  guide:    http://%s/guide.xml" % Handler.host, flush=True)
    print("  watch the console for the catch-up URLs the app asks for", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
