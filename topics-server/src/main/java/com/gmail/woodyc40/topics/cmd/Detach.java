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
package com.gmail.woodyc40.topics.cmd;

import com.gmail.woodyc40.topics.infra.JvmContext;
import com.gmail.woodyc40.topics.infra.command.CmdProcessor;

public class Detach implements CmdProcessor {
    @Override
    public String name() {
        return "detach";
    }

    @Override
    public String help() {
        return "Detaches from the currently attached JVM";
    }

    @Override
    public void process(String alias, String[] args) {
        if (JvmContext.getContext().getVm() == null) {
            System.out.println("not attached");
            return;
        }

        JvmContext.getContext().detach();
    }
}