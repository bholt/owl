#!/usr/bin/env python
import sh
import sys
from sh import sudo, ssh, docker
import time
from util import *
from os.path import expanduser as expand

MASTER = 'ibex'
AGENTS = ['platypus', 'sloth', 'rhinoceros']

DOCKER_PORT = 2376
SWARM_PORT = 4000
BRIDGE = 'swarm'

CONSUL = '10.100.1.10'
CONSUL_PORT = 8500
CONSUL_LOG = '/var/log/consul'

NETWORK = 'owl'

hosts = [MASTER] + AGENTS
machines = [ ssh.bake(host) for host in hosts ]

consul = "/homes/sys/bholt/bin/consul"

swarm_url = fmt("tcp://#{MASTER}:#{SWARM_PORT}")

swarm  = docker.bake(host=swarm_url)
master = ssh.bake(MASTER)


LIVE = {'_out': sys.stdout, '_err': sys.stderr}


def docker(host):
    return sh.docker.bake("--host=tcp://{}:{}".format(host, DOCKER_PORT))


def on(host):
    return ssh.bake(host)


def start(args=None, opt=None):
    # start Consul key/value store for service discovery
    on(MASTER).sudo(fmt("sh -c 'rm -rf /tmp/consul; nohup /homes/sys/bholt/bin/consul agent -server -bootstrap -data-dir /tmp/consul -node=master -bind=#{CONSUL} -client #{CONSUL} >#{CONSUL_LOG} 2>&1 &'"))

    time.sleep(4)
    
    for ssh in machines:
        
        # create new bridge network so our docker can run alongside the normal one
        if ssh.ip.link.show(BRIDGE, _ok_code=[0,1]).exit_code == 1:
            ssh.sudo.brctl.addbr(BRIDGE)
            ssh.sudo.ip.addr.add('10.20.30.1/24', 'dev', BRIDGE)
            ssh.sudo.ip.link.set('dev', 'swarm', 'up')
        
        # start docker daemon on remote host, headless via 'nohup', output to logfile
        ssh("sudo sh -c 'nohup docker daemon -H tcp://0.0.0.0:{dp} --exec-root=/var/run/docker.swarm --graph=/var/lib/docker.swarm --pidfile=/var/run/docker.swarm.pid --bridge={b} --cluster-advertise=ens1:{dp} --cluster-store=consul://{c}:{cp} >{log} 2>&1 &'".format(dp=DOCKER_PORT, c=CONSUL, cp=CONSUL_PORT, log="/var/log/docker.swarm", b=BRIDGE))
    
    time.sleep(1)
    # start Swarm manager
    nodelist = ','.join(["{}:{}".format(h, DOCKER_PORT) for h in hosts])
    docker(MASTER).run("--name=swarm", "-d", "--publish={}:2375".format(SWARM_PORT), "swarm:1.1.0", "--debug", "manage", "nodes://{}".format(nodelist))

    #swarm.network.create('--driver=overlay', NETWORK)


def stop(args=None, opt=None):
    
    for host in hosts:
        try:
            containers = docker(host).ps("-aq").stdout.split()
            if len(containers) > 0:
                docker(host).stop(*containers)
                docker(host).rm(*containers)
        except sh.ErrorReturnCode_1:
            puts("#{colored.yellow('[warning]')} no docker running on #{host}")
        
        on(host).sudo.pkill("-f", "[d]ocker.*tcp://", _ok_code=[0,1])
    
    on(MASTER).pkill("consul", _ok_code=[0,1])


def status(args=None, opt=None):
    print swarm.info()


def env(args=None, opt=None):
    puts("alias swarm='docker -H tcp://#{MASTER}:#{SWARM_PORT}'")
    puts("export DOCKER_HOST='tcp://#{MASTER}:#{SWARM_PORT}'")


def compose(args=None, opt=None):
    """ Invoke docker-compose command using Swarm. """
    puts(">>> docker-compose #{' '.join(args)} (with --host=#{swarm_url})")
    sh.docker_compose(*args, _ok_code=[0,1], _env={'DOCKER_HOST': swarm_url}, **LIVE)


def swarm_exec(node):
    return swarm.bake("exec", "-i", node)

owl_exec = swarm_exec("owl_owl_1")


def containers(filter):
    ps = swarm.ps()
    return [ line.split()[-1] for line in ps.split('\n') if filter in line ]


def add_keys(args=None, opt=None):
    if 'cass' in opt.containers:
        cons = containers('owl_cass')
    elif 'all' in opt.containers:
        cons = containers('owl_')
    else:
        cons = opt.containers

    for c in cons:
        puts(colored.yellow("#{c}>> ") + "add keys")
        ex = swarm_exec(c)
        ex.sh(c='mkdir -p ~/.ssh')
        ex.sh(c='cat > ~/.ssh/id_rsa.pub', _in=open(expand("~/.ssh/id_rsa.pub")))
        ex.sh(c='cat > ~/.ssh/id_rsa; chmod go-r ~/.ssh/id_rsa', _in=open(expand("~/.ssh/id_rsa")))
        ex.sh(c='cat >> ~/.ssh/config', _in=open(expand("~/.ssh/bistromath.config")))
        ex.sh(c='cat >> ~/.bashrc', _in="up(){ pushd /src >/dev/null; rsync -a bistromath:/sync/ipa/owl . --exclude=target/ --exclude=.idea/; popd >/dev/null; };\n")

def cass_exec(args=None, opt=None):
    for node in containers('owl_cass'):
        ex = swarm_exec(node)
        puts(colored.yellow("#{node}>> ") + ' '.join(args))
        swarm_exec(node)(*args)

def reservations(args=None, opt=None):
    if args is not None:
        args = ' '.join(args)

    for c in containers('owl_cass'):
        puts(colored.yellow("#{c}>> ") + "ipa.ReservationServer #{args}")
        swarm_exec(c).sh(c='pkill -f ipa.ReservationServer', _ok_code=[0,143])
        script = fmt('source ~/.bashrc; up; cd /src/owl; exec sbt "run-main ipa.ReservationServer" #{args} >/opt/docker/service.log 2>&1')
        o = swarm("exec", "-d", c, "bash", "-c", script)


def containers_str(prefix='owl_'):
    containers = [ l.split()[-1] for l in swarm.ps().split('\n') if '/owl_' in l ]
    cmap = {c: n for n, c in [c.split('/') for c in containers]}
    return yaml.safe_dump(cmap).strip()


if __name__ == '__main__':
    from argparse import ArgumentParser
    parser = ArgumentParser()
    subparsers = parser.add_subparsers(help='Commands help')
    commands = {}

    def add_command(command, callback):
        subp = subparsers.add_parser(command, add_help=False)
        subp.set_defaults(command=command)
        commands[command] = callback
        return subp

    add_command('start', start)
    add_command('stop', stop)
    add_command('up', start)  # alias
    add_command('down', stop) # alias

    add_command('status', status)
    add_command('env', env)
    add_command('compose', compose)

    cmd = add_command('add_keys', add_keys)
    cmd.add_argument('containers', type=str, nargs='+',
                      help='Names of containers to add keys to (or "all" or "cass").')

    add_command('cass_exec', cass_exec)
    add_command('reservations', reservations)

    opt, extra = parser.parse_known_args()
    if opt.command in commands:
        commands[opt.command](extra, opt)
    else:
        print 'invalid command'
        parser.print_help()
