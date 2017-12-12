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
package com.gmail.woodyc40.topics.protocol;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

@ThreadSafe
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SignalRegistry {
    private static int ID_COUNTER;
    private static final Map<Class<? extends SignalOut>, Integer> OUT_SIGNALS = new HashMap<>();
    private static final Map<Integer, Constructor<? extends SignalIn>> IN_SIGNALS = new HashMap<>();

    static {
        in(SignalInInit.class);
        out(SignalOut.class);
    }

    private static void in(Class<? extends SignalIn> signal) {
        try {
            int id = ID_COUNTER++;
            IN_SIGNALS.put(id, signal.getDeclaredConstructor());
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private static void out(Class<? extends SignalOut> signal) {
        int id = ID_COUNTER++;
        OUT_SIGNALS.put(signal, id);
    }

    public static SignalIn readSignal(int id) {
        Constructor<? extends SignalIn> constructor = IN_SIGNALS.get(id);
        if (constructor == null) {
            throw new RuntimeException("No signal: IN " + id);
        }

        try {
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    public static int writeSignal(SignalOut out) {
        Integer integer = OUT_SIGNALS.get(out.getClass());
        if (integer == null) {
            throw new IllegalStateException("No signal OUT: " + out.getClass().getSimpleName());
        }

        return integer;
    }

    public static void print() {
        System.out.println("IN TABLE");
        IN_SIGNALS.forEach((k, v) -> System.out.println(k + ": " + v.getDeclaringClass().getSimpleName()));

        System.out.println();
        System.out.println("OUT TABLE");
        OUT_SIGNALS.forEach((k, v) -> System.out.println(v + ": " + k.getSimpleName()));
    }
}