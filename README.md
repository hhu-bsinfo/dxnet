# DXNet: Event-Driven High Performance Message Passing for Java Applications

DXNet is developed by the [operating systems group](http://www.cs.hhu.de/en/research-groups/operating-systems.html)
of the department of computer science of the Heinrich-Heine-University 
Düsseldorf. DXNet is stand-alone and can be used with existing Java applications
but is also part of the distributed in-memory key-value store [DXRAM](https://github.com/hhu-bsinfo/dxram).

DXNet is a Java open-source network library positioned between RMI and MPI.
DXNet provides a high performance event-driven message passing system for
Java applications but differs from MPI by also providing a custom parallel
serialization for Java objects, automatic connection management, and a very
efﬁcient event-driven message receiving approach including a concurrent
deserialization. DXNet does not support MPI’s communication routines like
scatter or gather but offers high throughput asynchronous message passing
as well as synchronous request/response communication with very low latency.
Finally, it is open for supporting different transport protocols. It already
supports TCP with Java NIO and reliable verbs for Inﬁniband.
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

* Highly concurrent and transport agnostic architecture
* Zero-copy, parallel de-/serialization of Java objects
* Lock-free, event-driven message handling
* Supporting 5 GBit/s Ethernet and 56 GBit/s Inﬁniband networks

# Quick start guide
## Requirements
DXNet requires Java 1.8 to run on a Linux distribution of your choice
(MacOSX should work as well but is not supported, officially).

### Dependencies
The following dependencies are required and are already included with
DXNet in the "lib" folder:
* ant-contrib (0.6) for building
* gson (2.7) for DXNet
* Log4j2 (2.7) for DXNet

## Building
A build script (Ant) is included for building the project. Ensure Ant
is installed and run *build.sh*. The build output is located in
the *build/* sub-directory.

## DXNetMain: network benchmark
The DXNetMain class can be used to check and evaluate your setup. It provides loopback and end-to-end communication.

Configuration:
* Create a default configuration file: 
```
java -Dlog4j.configurationFile=config/log4j.xml -cp lib/gson-2.7.jar:lib/log4j-api-2.7.jar:lib/log4j-core-2.7.jar:dxnet.jar de.hhu.bsinfo.dxnet.DXNetMain config/dxnet.json
```
* Node setup: the configuration file contains a section *m_nodesConfig* which defines the NodeIDs, IPs and Ports of all participating nodes. For a setup with two nodes the section could look like this:
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
Execution: starting a test with two nodes sending 1,000,000 1 KB messages each
* On 10.0.0.63:
```
java -Dlog4j.configurationFile=config/log4j.xml -cp lib/gson-2.7.jar:lib/log4j-api-2.7.jar:lib/log4j-core-2.7.jar:dxnet.jar de.hhu.bsinfo.dxnet.DXNetMain config/dxnet.json 0 1000000 1024 2 6 7
```
* On 10.0.0.64 (start within the next 3 seconds):
```
java -Dlog4j.configurationFile=config/log4j.xml -cp lib/gson-2.7.jar:lib/log4j-api-2.7.jar:lib/log4j-core-2.7.jar:dxnet.jar de.hhu.bsinfo.dxnet.DXNetMain config/dxnet.json 0 1000000 1024 2 7 6
```

For a more convenient setup and execution use the deploy script: https://github.com/hhu-bsinfo/cdepl

## InfiniBand
If you want to use InfiniBand instead of Ethernet network:
* You need a cluster with InfiniBand hardware and software stack
installed
* Download and build [ibdxnet](https://github.com/hhu-bsinfo/ibdxnet) by following the instructions in the readme file of the repository.
* Grab the compiled JNI binding(s) and put it/them into the *jni* subfolder.
* Open the *dxnet.conf* and enable InfiniBand
(under *CoreConfig*):
```
"m_device": Infiniband
```

Note: Depending on how you set up your environment, DXNet/Ibdxnet needs
permissions to pin memory (capability: CAP_IPC_LOCK).

# License

Copyright (C) 2017 Heinrich-Heine-Universitaet Duesseldorf, 
Institute of Computer Science, Department Operating Systems. 
Licensed under the [GNU General Public License](LICENSE).

