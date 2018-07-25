# DXNet: Event-Driven High Performance Message Passing for Java Applications
DXNet is developed by the [operating systems group](http://www.cs.hhu.de/en/research-groups/operating-systems.html)
of the department of computer science of the Heinrich-Heine-University 
Düsseldorf. DXNet is stand-alone and can be used with existing Java applications
but is also part of the distributed in-memory key-value store [DXRAM](https://github.com/hhu-bsinfo/dxram).

DXNet is a Java open-source network library positioned between RMI and MPI.
DXNet provides a high performance event-driven message passing system for
Java applications but differs from MPI by also providing a custom parallel
serialization for Java objects, automatic connection management, and a very
efficient event-driven message receiving approach including a concurrent
deserialization. DXNet does not support MPI’s communication routines like
scatter or gather but offers high throughput asynchronous message passing
as well as synchronous request/response communication with very low latency.
Furthermore, it is open for supporting different transport protocols. It already
supports TCP with Java NIO and reliable verbs with InﬁniBand.
DXNet achieves high performance and low latency by using lock-free data
structures, zero-copy and zero-allocation. The outgoing ring buffer and queue
structures are complemented by different thread parking strategies guarantying
low latency by avoiding CPU overload.

# Important
DXNet is a research project that's under development. We do not 
recommend using the system in a production environment or with 
production data without having an external backup. Expect to encounter 
bugs. However, we are looking forward to bug reports and code 
contributions.

# Features
* Asynchronous and synchronous messaging primitives
* Highly concurrent and transport agnostic architecture
* Zero-copy, parallel de-/serialization of Java objects
* Lock-free, event-driven message handling
* Supporting 5 GBit/s Ethernet and 56 GBit/s InﬁniBand networks

# How to Build and Run
## Requirements
DXNet requires Java 1.8 to run on a Linux distribution of your choice
(MacOSX might work as well but is not supported, officially).

## Building
The script *build.sh* bootstraps our build system which is using gradle to build DXNet. The build output is located
in *build/dist* either as directory (dxnet) or zip-package (dxnet.zip).

## DXNetMain: Network Benchmark
The dxnet jar-file contains a built in benchmark that can be run to evaluate the performance of DXNet and a selected
transport on a cluster with arbitrary number of nodes.

Deploy the build output to your cluster and run DXNet by executing the script *dxnet* in the *bin* subfolder:
```
./bin/dxnet ./config/dxnet.jsonf
```

If there is no configuration file, it will create one with default values before starting the benchmark.

Setup your nodes in the configuration file, by adding entries like shown in the example below. Assign arbitrary nodeIds
to each node added with their ip addresses and ports. Example for two nodes:
```
"m_nodesConfig": [
  {
    "m_nodeId": 6,
    "m_address": {
      "m_ip": "10.0.0.63",
      "m_port": 22222
    }
  },
  {
    "m_nodeId": 7,
    "m_address": {
      "m_ip": "10.0.0.64",
      "m_port": 22222
    }
  }
],
```

The nodeIds chosen here have to match the ones you select when starting the DXNet benchmark (see further below).

The benchmark supports the following workloads:
* 0: Messages with pooling message objects, i.e. re-use message objects
* 1: Messages without pooling, i.e. create a new message objects for every message to send
* 2: Request-Response with pooling message objects, i.e. re-use request objects
* 3: Request-Response without pooling, i.e. create a new request objects for every request to send

Usage information:
```
Args: <config_file> <print interval ms> <workload> <send count> <recv count> <size payload> <send/app threads> <node id> [send target node ids ...]
```

For example, to run workload 0, send 1000 messages, receive 1000 messages, 32 byte size with one application thread and
two nodes, run the following commands:
```
On the first node:
./bin/dxnet ./config/dxnet.json 1000 0 1000 1000 16 1 0 1

On the second node:
./bin/dxnet ./config/dxnet.json 1000 0 1000 1000 16 1 1 0
```

You can run this more than two nodes as well. Make sure to set the right receive counts according to how many nodes
are set as 'target to send to' nodes (refer to usage information). Example for 4 nodes all-to-all:
```
Node 1:
./bin/dxnet ./config/dxnet.json 1000 0 3000 3000 16 1 0 1 2 3

Node 2:
./bin/dxnet ./config/dxnet.json 1000 0 3000 3000 16 1 1 0 2 3

Node 3:
./bin/dxnet ./config/dxnet.json 1000 0 3000 3000 16 1 2 0 1 3

Node 4:
./bin/dxnet ./config/dxnet.json 1000 0 3000 3000 16 1 3 0 1 2
```

Each node sends 3000 messages in total but distributes them equally to all other (three) nodes. Hence, every node
also has to receive 3000 messages from the (three) other nodes.

For a more convenient setup and execution use our [deploy script](https://github.com/hhu-bsinfo/cdepl).

## InfiniBand
If you want to use InfiniBand instead of Ethernet network:
* You need a cluster with InfiniBand hardware and software stack
installed
* Download and build [ibdxnet](https://github.com/hhu-bsinfo/ibdxnet) by following the instructions in the readme
file of the repository.
* Grab the compiled JNI binding(s) and put it/them into the *jni* subfolder.
* Open the *dxnet.conf* and enable InfiniBand
(under *CoreConfig*):
```
"m_device": Infiniband
```

Note: Depending on how you set up your environment, DXNet/Ibdxnet needs
permissions to pin memory (capability: CAP_IPC_LOCK).

# License

Copyright (C) 2018 Heinrich-Heine-Universitaet Duesseldorf,
Institute of Computer Science, Department Operating Systems. 
Licensed under the [GNU General Public License](LICENSE).

