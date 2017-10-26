/*
 * JDB - Java Debugger
 * Copyright 2017 Johnny Cao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gmail.woodyc40.topics.infra;

import com.gmail.woodyc40.topics.Main;
import com.gmail.woodyc40.topics.cmd.LsJvm;
import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.tools.jdi.ProcessAttachingConnector;
import lombok.*;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Holds the current state of the JVM which is being
 * debugged, i.e. breakpoints and source paths.
 */
@ThreadSafe
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JvmContext {
    /** The singleton instance of the JVM context */
    @Getter private static final JvmContext context = new JvmContext();

    /** Currently attached JVM PID */
    @GuardedBy("this")
    @Getter private int currentPid = -1;
    /** The virtual machine that is currently attached */
    @GuardedBy("this")
    @Getter private VirtualMachine vm;
    /** Should the VM exit() when detached? */
    @GuardedBy("this")
    private boolean closeOnDetach;
    /** The listener thread for VM breakpoints */
    @GuardedBy("this")
    private Thread breakpointListener;
    /** The collection of paths leading to class sources */
    @Getter private final Map<String, Path> sourcePath = new
            ConcurrentHashMap<>();

    /** The class that is currently being modified */
    @GuardedBy("this")
    @Getter @Setter private ReferenceType currentRef;

    /** The previous breakpoint frames */
    @Getter private final Queue<List<StackFrame>> previousFrames = new ConcurrentLinkedQueue<>();
    /** Lock used to protect the breakpoint events */
    @Getter private final Object lock = new Object();
    /** The current breakpoint that is active on the VM */
    @GuardedBy("lock")
    @Getter @Setter private BreakpointEvent currentBreakpoint;
    /** The current eventSet used by the current breakpoint */
    @GuardedBy("lock")
    @Getter @Setter private EventSet resumeSet;
    /** Mapping of FQN:LN breakpoint info to disable breakpoints */
    @GuardedBy("this")
    @Getter private final Map<String, BreakpointRequest> breakpoints = new HashMap<>();

    /**
     * Sets whether or not the VM should call exit() when
     * it is detached.
     *
     * @param close {@code true} to exit().
     */
    public synchronized void setCloseOnDetach(boolean close) {
        this.closeOnDetach = close;
    }

    /**
     * Sets the current JVM context to that of a JVM running
     * at the given PID number.
     *
     * @param pid the process ID to attach
     */
    public synchronized void attach(int pid) {
        if (pid < 0) {
            System.out.println("failed");
            return;
        }

        if (this.currentPid == pid) {
            System.out.println("process already attached");
            return;
        }

        if (this.currentPid > 0) {
            System.out.println();
            System.out.println("Currently attached to " + this.currentPid);
            String yn = Main.prompt("Do you really want to attach [Y/n]? ");
            if (!yn.toLowerCase().equals("y") && !yn.toLowerCase().equals("yes")) {
                System.out.println("abort");
                return;
            }
        }

        String procData;
        try {
            procData = LsJvm.getAvailablePids().get(pid);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }

        if (procData == null) {
            System.out.println("no JVM with PID " + pid);
            return;
        }

        System.out.println("Attaching to " + pid + ": " + procData + "...");
        this.currentPid = pid;

        List<AttachingConnector> connectors = Bootstrap.virtualMachineManager().attachingConnectors();
        ProcessAttachingConnector pac = null;
        for (AttachingConnector connector : connectors) {
            if (connector.name().equals("com.sun.jdi.ProcessAttach")) {
                pac = (ProcessAttachingConnector) connector;
            }
        }

        if (pac == null) {
            System.out.println("ProcessAttach not found");
            return;
        }

        Map<String, Connector.Argument> args = pac.defaultArguments();
        Connector.Argument arg = args.get("pid");
        if (arg == null) {
            System.out.println("corrupt transport");
            return;
        }

        arg.setValue(String.valueOf(pid));
        try {
            this.vm = pac.attach(args);
            this.breakpointListener = new Thread(new Runnable() {
                final EventQueue queue = vm.eventQueue();
                @Override public void run() {
                    while (true) {
                        try {
                            EventSet eventSet = this.queue.remove();

                            for (Event event : eventSet) {
                                if (event instanceof BreakpointEvent) {
                                    BreakpointEvent e = (BreakpointEvent) event;

                                    List<StackFrame> frames = new ArrayList<>();
                                    for (int i = 0; i < Integer.MAX_VALUE; i++) {
                                        try {
                                            frames.add(e.thread().frame(i));
                                        } catch (IncompatibleThreadStateException e1) {
                                            e1.printStackTrace();
                                        } catch (IndexOutOfBoundsException e1) {
                                            break;
                                        }
                                    }
                                    previousFrames.add(frames);

                                    synchronized (lock) {
                                        currentBreakpoint = e;
                                        resumeSet = eventSet;
                                    }
                                    Main.printAsync("Hit breakpoint " + e.location().sourceName() + ":" + e.location().lineNumber());

                                    String string = lookupLine(e.location().declaringType().name(), e.location().lineNumber(), 3);
                                    if (string != null && !string.isEmpty()) {
                                        Main.printAsync("Code context:");
                                        Main.printAsync(string);
                                    }
                                }
                            }
                        } catch (AbsentInformationException e) {
                            throw new RuntimeException(e);
                        } catch (VMDisconnectedException e) {
                            synchronized (this) {
                                breakpointListener = null;
                            }
                            JvmContext.this.detach(true);
                            break;
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            });
            this.breakpointListener.start();
        } catch (IOException | IllegalConnectorArgumentsException e) {
            throw new RuntimeException(e);
        }

        System.out.println("Successfully attached to " + pid);
    }

    /**
     * Detaches from the currently attached JVM, or fails.
     *
     * @param async if being detached from another thread
     */
    public synchronized void detach(boolean async) {
        try {
            if (this.breakpointListener != null) {
                this.breakpointListener.interrupt();
                this.breakpointListener.join();

                for (BreakpointRequest request : this.breakpoints.values()) {
                    request.disable();
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        this.currentRef = null;
        this.sourcePath.clear();
        this.previousFrames.clear();
        synchronized (this.lock) {
            this.currentBreakpoint = null;
            this.resumeSet = null;
        }
        this.breakpoints.clear();

        System.out.println(closeOnDetach);
        if (this.closeOnDetach) {
            this.vm.exit(3);
        } else if (this.breakpointListener != null) {
            this.breakpointListener = null;
            this.vm.dispose();
        }
        this.vm = null;

        if (async) {
            Main.printAsync("Detached from JVM " + this.currentPid);
        } else {
            System.out.println("Detached from JVM " + this.currentPid);
        }
        this.currentPid = -1;
    }

    /**
     * Looksup the source line with the given location
     * information and amount of context.
     *
     * @param cls the class to lookup
     * @param ln the line number to use
     * @param context the number of lines before and after
     * to include
     * @return the source line
     */
    public String lookupLine(String cls, int ln, int context) {
        Path path = this.sourcePath.get(cls);
        if (path == null) {
            return null;
        } else {
            try {
                StringBuilder builder = new StringBuilder();
                BufferedReader reader = Files.newBufferedReader(path);
                for (int i = 1; ; i++) {
                    String line = reader.readLine();
                    if (i >= ln - context && i <= ln + context) {
                        if (i == ln + context) {
                            builder.append(line);
                            break;
                        }

                        if (i == ln) {
                            builder.append('>');
                        }

                        builder.append(line).append('\n');
                    }
                }

                return builder.toString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}