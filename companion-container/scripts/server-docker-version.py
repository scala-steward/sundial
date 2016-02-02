#!/usr/bin/python

import json
import uhttplib

conn = uhttplib.UnixHTTPConnection('/var/hostrun/docker.sock')
conn.request('GET', '/version')
resp = conn.getresponse()
info = json.loads(resp.read())

print info['Version']
