package com.orchpilot.workflow.plugins.azure;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import java.util.*;
final class MapConfiguration implements NodeConfiguration{private final Map<String,Object> values;MapConfiguration(Map<String,Object> v){values=new LinkedHashMap<>(v);}public Optional<Object> find(String k){return Optional.ofNullable(values.get(k));}public Map<String,Object> asMap(){return Collections.unmodifiableMap(values);}}
