/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hw.langchain.chains.base;

import cn.hutool.core.map.MapUtil;
import com.google.common.collect.Maps;
import com.hw.langchain.schema.BaseMemory;

import org.apache.commons.lang3.StringUtils;

import reactor.core.publisher.Flux;

import java.util.*;

/**
 * Base interface that all chains should implement.
 *
 * @author HamaWhite
 */
public abstract class Chain {

    protected BaseMemory memory;

    public abstract String chainType();

    /**
     * Retrieves the list of input keys that this chain expects.
     *
     * @return the list of input keys
     */
    public abstract List<String> inputKeys();

    /**
     * Retrieves the list of output keys that this chain expects.
     *
     * @return the list of output keys
     */
    public abstract List<String> outputKeys();

    /**
     * Check that all inputs are present
     */
    private void validateInputs(Map<String, Object> inputs) {
        Set<String> missingKeys = new HashSet<>(inputKeys());
        missingKeys.removeAll(inputs.keySet());
        if (!missingKeys.isEmpty()) {
            throw new IllegalArgumentException(String.format("Missing some input keys: %s", missingKeys));
        }
    }

    private void validateOutputs(Map<String, String> outputs) {
        Set<String> missingKeys = new HashSet<>(outputKeys());
        missingKeys.removeAll(outputs.keySet());
        if (!missingKeys.isEmpty()) {
            throw new IllegalArgumentException(String.format("Missing some output keys: %s", missingKeys));
        }
    }

    /**
     * Runs the logic of this chain and returns the output.
     *
     * @param inputs the inputs to be processed by the chain
     * @return a map containing the output generated by the chain
     */
    protected abstract Map<String, String> innerCall(Map<String, Object> inputs);

    /**
     * Runs the logic of this chain and returns the async output.
     *
     * @param inputs the inputs to be processed by the chain
     * @return a map flux containing the output generated event by the chain
     */
    protected Flux<Map<String, String>> asyncInnerCall(Map<String, Object> inputs) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Run the logic of this chain and add to output if desired.
     *
     * @param input             single input if chain expects only one param.
     * @param returnOnlyOutputs boolean for whether to return only outputs in the response.
     *                          If True, only new keys generated by this chain will be returned.
     *                          If False, both input keys and new keys generated by this chain will be returned.
     *                          Defaults to False.
     */
    public Map<String, String> call(Object input, boolean returnOnlyOutputs) {
        Map<String, Object> inputs = prepInputs(input);
        return call(inputs, returnOnlyOutputs);
    }

    /**
     * Run the logic of this chain and add to output if desired.
     *
     * @param inputs            Dictionary of inputs.
     * @param returnOnlyOutputs boolean for whether to return only outputs in the response.
     *                          If True, only new keys generated by this chain will be returned.
     *                          If False, both input keys and new keys generated by this chain will be returned.
     *                          Defaults to False.
     */
    public Map<String, String> call(Map<String, Object> inputs, boolean returnOnlyOutputs) {
        inputs = prepInputs(inputs);
        Map<String, String> outputs = innerCall(inputs);
        return prepOutputs(inputs, outputs, returnOnlyOutputs);
    }

    public Flux<Map<String, String>> asyncCall(Object input, boolean returnOnlyOutputs) {
        Map<String, Object> inputs = prepInputs(input);
        return asyncCall(inputs, returnOnlyOutputs);
    }

    public Flux<Map<String, String>> asyncCall(Map<String, Object> inputs, boolean returnOnlyOutputs) {
        inputs = prepInputs(inputs);
        Flux<Map<String, String>> outputs = asyncInnerCall(inputs);
        return asyncPrepOutputs(inputs, outputs, returnOnlyOutputs);
    }

    /**
     * Validate and prep outputs.
     */
    private Map<String, String> prepOutputs(Map<String, Object> inputs, Map<String, String> outputs,
            boolean returnOnlyOutputs) {
        validateOutputs(outputs);
        if (memory != null) {
            memory.saveContext(inputs, outputs);
        }
        if (returnOnlyOutputs) {
            return outputs;
        } else {
            Map<String, String> result = Maps.newHashMap();
            inputs.forEach((k, v) -> result.put(k, v.toString()));
            result.putAll(outputs);
            return result;
        }
    }

    /**
     * Validate and async prep outputs.
     */
    private Flux<Map<String, String>> asyncPrepOutputs(Map<String, Object> inputs, Flux<Map<String, String>> outputs,
            boolean returnOnlyOutputs) {
        Map<String, String> collector = Maps.newHashMap();
        return outputs.doOnNext(this::validateOutputs)
                .doOnNext(m -> m.forEach((k, v) -> collector.compute(k, (s, old) -> {
                    if (StringUtils.equals(s, outputKeys().get(0))) {
                        return old + v;
                    } else {
                        return StringUtils.firstNonBlank(old, v);
                    }
                }))).map(m -> {
                    if (returnOnlyOutputs) {
                        return m;
                    } else {
                        Map<String, String> result = Maps.newHashMap();
                        inputs.forEach((k, v) -> result.put(k, v.toString()));
                        result.putAll(m);
                        return result;
                    }
                }).doOnComplete(() -> {
                    if (memory != null) {
                        memory.saveContext(inputs, collector);
                    }
                });
    }

    /**
     * Validate and prep inputs.
     */
    private Map<String, Object> prepInputs(Object input) {
        Set<String> inputKeys = new HashSet<>(inputKeys());
        if (memory != null) {
            // If there are multiple input keys, but some get set by memory so that only one is not set,
            // we can still figure out which key it is.
            Set<String> memoryVariables = new HashSet<>(memory.memoryVariables());
            inputKeys.removeAll(memoryVariables);
        }
        if (inputKeys.size() != 1) {
            throw new IllegalArgumentException(
                    String.format(
                            "A single string input was passed in, but this chain expects multiple inputs (%s). " +
                                    "When a chain expects multiple inputs, please call it by passing in a dictionary, "
                                    +
                                    "eg `chain(Map.of('foo', 1, 'bar', 2))`",
                            inputKeys));
        }
        return MapUtil.of(new ArrayList<>(inputKeys).get(0), input);
    }

    /**
     * Validate and prep inputs.
     */
    public Map<String, Object> prepInputs(Map<String, Object> inputs) {
        Map<String, Object> newInputs = new HashMap<>(inputs);
        if (memory != null) {
            Map<String, Object> externalContext = memory.loadMemoryVariables(inputs);
            newInputs.putAll(externalContext);
        }
        validateInputs(newInputs);
        return newInputs;
    }

    /**
     * Run the chain as text in, text out
     */
    public String run(Object args) {
        if (outputKeys().size() != 1) {
            throw new IllegalArgumentException(
                    "The `run` method is not supported when there is not exactly one output key. Got " + outputKeys()
                            + ".");
        }
        return call(args, false).get(outputKeys().get(0));
    }

    /**
     * Run the chain as multiple variables, text out.
     */
    public String run(Map<String, Object> args) {
        if (outputKeys().size() != 1) {
            throw new IllegalArgumentException(
                    "The `run` method is not supported when there is not exactly one output key. Got " + outputKeys()
                            + ".");
        }
        return call(args, false).get(outputKeys().get(0));
    }

    /**
     * Run the chain as text in, text out async
     */
    public Flux<String> asyncRun(Object args) {
        if (outputKeys().size() != 1) {
            throw new IllegalArgumentException(
                    "The `run` method is not supported when there is not exactly one output key. Got " + outputKeys()
                            + ".");
        }
        return asyncCall(args, false).map(m -> m.get(outputKeys().get(0)));
    }

    /**
     * Run the chain as multiple variables, text out async.
     */
    public Flux<String> asyncRun(Map<String, Object> args) {
        if (outputKeys().size() != 1) {
            throw new IllegalArgumentException(
                    "The `run` method is not supported when there is not exactly one output key. Got " + outputKeys()
                            + ".");
        }
        return asyncCall(args, false).map(m -> m.get(outputKeys().get(0)));
    }

}
