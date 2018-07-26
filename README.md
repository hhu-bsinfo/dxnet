# DXNet: Event-Driven High Performance Message Passing for Java Applications

[![Build Status](https://travis-ci.org/hhu-bsinfo/dxnet.svg?branch=development)](https://travis-ci.org/hhu-bsinfo/dxnet)

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
./bin/dxnet ./config/dxnet.json
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

Usage information:
```
Args: <config_file> <print interval ms> <workload> <send count> <recv count> <size payload> <send/app threads> <node id> [send target node ids ...]
  config_file: Path to the config file to use (e.g. ./config/dxnet.json). Creates new config with default value if file does not exist
  print interval ms: Interval to print status/progress output during the benchmark (e.g. 1000 for every second)
  workload: Workload to execute
     0: Messages with pooling message objects, i.e. re-use message objects
     1: Messages without pooling, i.e. create a new message objects for every message to send
     2: Request-Response with pooling message objects, i.e. re-use request objects
     3: Request-Response without pooling, i.e. create a new request objects for every request to send
  send count: Total number of messages to send (equally distributed to all target nodes)
  recv count: Total number of messages to receive from all nodes that target messages to the current one
  size payload: Size of a single message (payload size)
  send/app threads: Number of thread to spawn that send messages concurrently
  node id: Node id to set for the current instance
  send target node ids...: A list of node IDs of target nodes to send to. Receive only is also possible (e.g. for uni-direcitonal benchmarks)
```

For example, to run workload 0, send 1000 messages, receive 1000 messages, 32 byte size with one application thread and
two nodes, run the following commands:
```
On the first node:
./bin/dxnet ./config/dxnet.json 1000 0 1000 1000 32 1 0 1

On the second node:
./bin/dxnet ./config/dxnet.json 1000 0 1000 1000 32 1 1 0
```

You can run this more than two nodes as well. Make sure to set the right receive counts according to how many nodes
are set as 'target to send to' nodes (refer to usage information). Example for 4 nodes all-to-all:
```
Node 1:
./bin/dxnet ./config/dxnet.json 1000 0 3000 3000 32 1 0 1 2 3

Node 2:
./bin/dxnet ./config/dxnet.json 1000 0 3000 3000 32 1 1 0 2 3

Node 3:
./bin/dxnet ./config/dxnet.json 1000 0 3000 3000 32 1 2 0 1 3

Node 4:
./bin/dxnet ./config/dxnet.json 1000 0 3000 3000 32 1 3 0 1 2
```

Each node sends 3000 messages in total but distributes them equally to all other (three) nodes. Hence, every node
also has to receive 3000 messages from the (three) other nodes.

Example output for benchmark results of a single instance:
```
[RESULTS]
[RESULTS PARAMS: Workload=0, MsgSize=43, MsgPayloadSize=32, Threads=1, MsgHandlers=2]
[RESULTS SEND: Runtime=0.018 sec, Msgs=1000, X=2.267 mb/s, XP=1.687 mb/s, XM=0.055270 milmsg/s]
[RESULTS RECV: Runtime=0.046 sec, Msgs=1000, X=0.893 mb/s, XP=0.665 mb/s, XM=0.021782 milmsg/s]
[RESULTS LATENCY: Msgs=1, Avg=42024.000 us, Min=42024.782 us, Max=42024.782 us, 95th=0.000 us, 99th=0.000 us, 99.9th=0.000 us]
[RESULTS ERRORS: ReqRespTimeouts=0]
```

*X* is the throughput including metadata/message overhead and *XP* the payload only throughput. *XM* is the number of
million messages per second. Latency does apply to request "messages", only, and can be ignored in this example.

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

