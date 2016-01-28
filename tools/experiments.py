#!/usr/bin/env python
# -*- coding: utf-8 -*-
import dataset
import sys
import itertools as it
import os
import re
import socket
import time
import json

from os.path import abspath, dirname, realpath
from os import environ as env
import requests

#########################
# External dependencies
import colors  # ansicolors
import sh
import pygments
from pygments.lexers import JsonLexer
from pygments.formatters import TerminalFormatter


def color(o, **args):
    return colors.color(str(o), **args)


def heading(text):
    return color(text, fg='black', style='underline+bold')


def note(text):
    return color(text, fg='black')


LIVE = {'_out': sys.stdout, '_err': sys.stderr}


def pretty_json(value):
    return pygments.highlight(unicode(json.dumps(value, indent=2, sort_keys=True), 'UTF-8'), JsonLexer(), TerminalFormatter(bg="dark"))

#########################

def notify_slack(msg):
    if 'IPA_SLACK_WEBHOOK' not in env:
        print '<slack notifications disabled>'
        return
    
    url = env['IPA_SLACK_WEBHOOK']
    data = {
        'username': 'Experiments',
        'icon_emoji': ':shipit:',
        'text': msg
    }
    requests.post(url, headers={'content-type': 'application/json'}, data=json.dumps(data))

RE_INTERPOLATOR = re.compile(r"{(.*?)}")


def fmt(s):
    return RE_INTERPOLATOR.sub(lambda m: str(eval(m.group(1))), str(s))


class FormatStream(object):
    def __init__(self, stream, color = None):
        self.stream = stream
        self.color = color

    def fmt(self, s, **colorargs):
        if self.color and 'fg' not in colorargs:
            colorargs['fg'] = self.color
        self.stream.write(color(fmt(s)+"\n", **colorargs))

out = FormatStream(sys.stdout)
err = FormatStream(sys.stderr, 'red')


def hostname():
    return socket.getfqdn()


def on_sampa_cluster():
    return 'sampa' in hostname()


def slurm_nodes():
    return [
        n.replace('n', 'i') for n in
        sh.scontrol.show.hostname(env['SLURM_NODELIST']).split()
    ]


def flatten_json(y):
    out = {}

    def flatten(x, name=''):
        if type(x) is dict:
            for a in x:
                flatten(x[a], name + a + '_')
        elif type(x) is list:
            i = 0
            for a in x:
                flatten(a, name + str(i) + '_')
                i += 1
        else:
            out[str(name[:-1])] = str(x)

    flatten(y)
    return out


def count_records(table, ignore=None, valid='total_time is not null', **params):
    if ignore is None:
        ignore = []
    ignore.append('sqltable')
    remain = {k: params[k] for k in params if k not in ignore}

    def query(q):
        print '#>', q
        return [dict(x) for x in DB.query(q)]

    def cmp(k, v):
        if type(v) == float:
            return "ABS({0}-{1})/{1} < 0.0001".format(k, v)
        elif type(v) == str and len(v) == 0:
            return "({0} = '' or {0} is null)".format(k)
        else:
            return "{0} = '{1}'".format(k, v)

    cond = ' and '.join([cmp(k, v) for k, v in remain.items()])

    try:
        tname = table.table.name
        r = query('SELECT count(*) as ct FROM %s WHERE %s and %s' % (tname, valid, cond))
        return r[0]['ct']
    except Exception as e:
        err.fmt("error with query: " + str(e))
        return 0


def cartesian(**params):
    return [dict(p) for p in it.product(*[zip(it.repeat(k), v) for k, v in params.items()])]


#################################################################################

blockade = sh.sudo.blockade

# Tasks to run before running any jobs
def before_all():

    print note("> creating up-to-date docker image")
    # (suppress 'sending build context' messages)
    for line in sh.sbt("docker:publishLocal", _iter=True):
        if not re.match(r"^\[info\] Sending build context.*$", line):
            print line

    print note("> initializing blockade")
    blockade.destroy(_ok_code=[0,1], **LIVE)
    blockade.up(**LIVE)

    def check(container):
        o = sh.grep(sh.docker.logs(container, _piped=True), "listening for CQL clients", _ok_code=[0,1])
        return len(o) > 0

    sys.stdout.write("Waiting for Cassandra to finish launching")
    while not check("owl_s3"):
        sys.stdout.write(".")
        sys.stdout.flush()
        time.sleep(1)
    print "done"

    time.sleep(5) # make sure Cassandra has finished initializing
    blockade.status(**LIVE)


def run(table, logfile, *args, **flags):
    # convert ipa_* flags to java properties & add to args
    # ipa_retwis_initial_users -> ipa.retwis.initial.users
    args = list(args)
    args.extend([
        "-D{}={}".format(k.replace('_','.'), flags[k])
        for k in flags if k.startswith('ipa_')
    ])

    if 'blockade_mode' in flags:
        mode = flags['blockade_mode']
        blockade(mode, "s1", "s2", "s3")

    try:
        cmd = sh.docker("exec", "owl_c1", "bin/owl", *args, _timeout=60*5, _iter=True)
        print ">", color(' '.join(cmd.cmd), fg='blue')
        for o in cmd:
            logfile.write(o)
            if opt.verbose:
                print o, # w/o extra newline

        # flatten & clean up metrics a bit
        metrics = {
            re.sub(r"owl\.\w+\.", "", k): v
            for k, v in flatten_json(json.loads(cmd.stderr)).items()
        }

        a.update(metrics)
        print pretty_json(a)
        table.insert(a)

    except (KeyboardInterrupt, sh.TimeoutException) as e:
        out.fmt("job cancelled")


if __name__ == '__main__':
    global opt
    from argparse import ArgumentParser
    parser = ArgumentParser()
    parser.add_argument('-t', '--target', type=int, default=1)
    parser.add_argument('-m', '--mode', type=str, default='owl')
    parser.add_argument('-f', '--failures', type=int, default=0)
    parser.add_argument('-n', '--machines', type=str, default="")
    parser.add_argument('--manual', type=str, default=None)
    parser.add_argument('--dry', dest='dry', action='store_true', default=False)
    parser.add_argument('-v', '--verbose', dest='verbose', action='store_true', default=False)

    if '--' in sys.argv:
        args_to_parse = sys.argv[1:sys.argv.index('--')]
        opt = parser.parse_args(args_to_parse)
        manual = sys.argv[sys.argv.index('--')+1:]
    else:
        opt = parser.parse_args()
        manual = None

    SRC = abspath(dirname(realpath(__file__)))
    os.chdir(SRC)

    if on_sampa_cluster():
        MACHINES = slurm_nodes()
    else:
        MACHINES = hostname()

    print 'machines:', MACHINES

    DB = dataset.connect(fmt("mysql:///ipa?read_default_file={env['HOME']}/.my.cnf"))
    table = DB[opt.mode]

    if manual:
        run(table, sys.stdout, ' '.join(manual),
            machines=','.join(MACHINES))
        exit(0)
    elif not opt.dry:
        # startup
        before_all()
        # print note('skipping before_all()')

    log = open(SRC + '/experiments.log', 'w')

    tag = sh.git.describe().stdout
    version = re.match(r"(\w+)(-.*)?", tag).group(1)
    machines = hostname()

    K= 1024
    nexp = 0

    for trial in range(1, opt.target+1):
        if not opt.dry:
            print '---------------------------------\n# starting trial', trial
        elif opt.dry and trial > 1:
            # only need to do one 'trial' to get all the counts if doing dry run
            continue
        for a in cartesian(
            ipa_version               = [version],
            ipa_output_json           = ['true'],

            ipa_replication_factor    = [3],
            ipa_reset                 = ['false'],
            ipa_retwis_generate       = ['true'],

            ipa_retwis_duration       = [60],
            ipa_retwis_initial_users  = [100],
            ipa_retwis_initial_tweets = [10],
            ipa_retwis_zipf           = ['1.0'],
            ipa_concurrent_requests   = [16, 128, 512, 2*K, 4*K, 8*K, 32*K],

            ipa_consistency           = ['strong', 'weak'],
            blockade_mode             = ['slow']  # 'fast'
        ):
            ct = count_records(table, ignore=[],
                               valid='meters_retwis_op_count is not null', **a)
            out.fmt("→ {color('count:',fg='cyan')} {color(ct,fg='yellow')}", fg='black')
            if opt.dry:
                continue
            if ct < trial:
                run(table, log, **a)
                nexp += 1
    
    notify_slack(fmt("Finished {nexp} experiments. :success:"))