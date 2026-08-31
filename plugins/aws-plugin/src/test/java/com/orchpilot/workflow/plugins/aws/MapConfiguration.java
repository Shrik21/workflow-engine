package com.orchpilot.workflow.plugins.aws;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import java.util.*;
final class MapConfiguration implements NodeConfiguration {
    private final Map<String,Object> values;
    MapConfiguration(Map<String,Object> values){this.values=new LinkedHashMap<>(values);}
    public Optional<Object> find(String key){return Optional.ofNullable(values.get(key));}
    public Map<String,Object> asMap(){return Collections.unmodifiableMap(values);}
}
