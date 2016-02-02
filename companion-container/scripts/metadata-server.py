#!/usr/bin/python

import socket
import sys
import boto.sdb
import os
from thread import *

if len(sys.argv) != 2:
    print "Usage: metadata-server.py TASK_ID"
    sys.exit()

TASK_ID = sys.argv[1]

HOST = ''
PORT = 13290

sdb_conn = boto.sdb.connect_to_region(os.environ['REGION'])
sdb_dom = sdb_conn.get_domain(os.environ['RESOURCE_SimpleDBDomain'])

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)

try:
    s.bind((HOST, PORT))
except socket.error as msg:
    print 'Bind failed. Error Code : ' + str(msg[0]) + ' Message ' + msg[1]
    sys.exit()

s.listen(10)

def clientthread(conn):
    while True:
        data = conn.recv(4096)
        if not data:
            break
        else:
            lines = data.splitlines()
            max_ts = 0
            doc = {}
            for line in lines:
                parts = line.split(" ")
                name = parts[0]
                value = parts[1]
                ts = parts[2]

                doc[name] = value

                max_ts = max(max_ts, ts)
            doc['last_updated'] = max_ts

            sdb_dom.put_attributes(TASK_ID, doc)

    conn.close()

while 1:
    conn, addr = s.accept()
    start_new_thread(clientthread ,(conn,))

s.close()