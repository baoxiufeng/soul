/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dromara.soul.register.server.zookeeper;

import com.google.common.collect.Lists;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.I0Itec.zkclient.IZkDataListener;
import org.I0Itec.zkclient.ZkClient;
import org.dromara.soul.common.enums.RpcTypeEnum;
import org.dromara.soul.common.utils.CollectionUtils;
import org.dromara.soul.common.utils.GsonUtils;
import org.dromara.soul.register.common.config.SoulRegisterCenterConfig;
import org.dromara.soul.register.common.dto.MetaDataRegisterDTO;
import org.dromara.soul.register.common.dto.URIRegisterDTO;
import org.dromara.soul.register.common.path.ZkRegisterPathConstants;
import org.dromara.soul.register.server.api.SoulServerRegisterPublisher;
import org.dromara.soul.register.server.api.SoulServerRegisterRepository;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Zookeeper register center.
 *
 * @author lw1243925457
 */
@Slf4j
public class ZookeeperServerRegisterRepository implements SoulServerRegisterRepository {
    
    private static final EnumSet<RpcTypeEnum> METADATA_SET = EnumSet.of(RpcTypeEnum.DUBBO, RpcTypeEnum.GRPC, RpcTypeEnum.HTTP, RpcTypeEnum.SPRING_CLOUD, RpcTypeEnum.SOFA, RpcTypeEnum.TARS);
    
    private static final EnumSet<RpcTypeEnum> URI_SET = EnumSet.of(RpcTypeEnum.GRPC, RpcTypeEnum.HTTP, RpcTypeEnum.TARS);
    
    private final SoulServerRegisterPublisher publisher;
    
    private ZkClient zkClient;
    
    public ZookeeperServerRegisterRepository(final SoulServerRegisterPublisher publisher, final SoulRegisterCenterConfig soulRegisterCenterConfig) {
        this.publisher = publisher;
        init(soulRegisterCenterConfig);
    }
    
    @Override
    public void init(final SoulRegisterCenterConfig config) {
        Properties props = config.getProps();
        int zookeeperSessionTimeout = Integer.parseInt(props.getProperty("zookeeperSessionTimeout", "3000"));
        int zookeeperConnectionTimeout = Integer.parseInt(props.getProperty("zookeeperConnectionTimeout", "3000"));
        this.zkClient = new ZkClient(config.getServerLists(), zookeeperSessionTimeout, zookeeperConnectionTimeout);
        initSubscribe();
    }

    @Override
    public void close() {
        zkClient.close();
    }
    
    private void initSubscribe() {
        METADATA_SET.forEach(rpcTypeEnum -> subscribeMetaData(rpcTypeEnum.getName()));
        URI_SET.forEach(rpcTypeEnum -> subscribeURI(rpcTypeEnum.getName()));
    }
    
    private void subscribeURI(final String rpcType) {
        String contextPathParent = ZkRegisterPathConstants.buildURIContextPathParent(rpcType);
        List<String> contextPaths = zkClientGetChildren(contextPathParent);
        for (String contextPath : contextPaths) {
            watcherURI(rpcType, contextPath);
        }
        zkClient.subscribeChildChanges(contextPathParent, (parentPath, currentChildren) -> {
            if (CollectionUtils.isNotEmpty(currentChildren)) {
                for (String contextPath : currentChildren) {
                    watcherURI(rpcType, contextPath);
                }
            }
        });
    }
    
    private void subscribeMetaData(final String rpcType) {
        String contextPathParent = ZkRegisterPathConstants.buildMetaDataContextPathParent(rpcType);
        List<String> contextPaths = zkClientGetChildren(contextPathParent);
        for (String contextPath : contextPaths) {
            watcherMetadata(rpcType, contextPath);
        }
        zkClient.subscribeChildChanges(contextPathParent, (parentPath, currentChildren) -> {
            if (CollectionUtils.isNotEmpty(currentChildren)) {
                for (String contextPath : currentChildren) {
                    watcherMetadata(rpcType, contextPath);
                }
            }
        });
    }
    
    private void watcherMetadata(final String rpcType, final String contextPath) {
        String metaDataParentPath = ZkRegisterPathConstants.buildMetaDataParentPath(rpcType, contextPath);
        List<String> childrenList = zkClientGetChildren(metaDataParentPath);
        if (CollectionUtils.isNotEmpty(childrenList)) {
            childrenList.forEach(children -> {
                String realPath = ZkRegisterPathConstants.buildRealNode(metaDataParentPath, children);
                publishMetadata(zkClient.readData(realPath).toString());
                subscribeMetaDataChanges(realPath);
            });
        }
        zkClient.subscribeChildChanges(metaDataParentPath, (parentPath, currentChildren) -> {
            if (CollectionUtils.isNotEmpty(currentChildren)) {
                List<String> addSubscribePath = addSubscribePath(childrenList, currentChildren);
                addSubscribePath.stream().map(addPath -> {
                    String realPath = ZkRegisterPathConstants.buildRealNode(parentPath, addPath);
                    publishMetadata(zkClient.readData(realPath).toString());
                    return realPath;
                }).forEach(this::subscribeMetaDataChanges);
            
            }
        });
    }
    
    private void watcherURI(final String rpcType, final String contextPath) {
        String uriParentPath = ZkRegisterPathConstants.buildURIParentPath(rpcType, contextPath);
        List<String> childrenList = zkClientGetChildren(uriParentPath);
        if (CollectionUtils.isNotEmpty(childrenList)) {
            registerURIChildrenList(childrenList, uriParentPath);
        }
        zkClient.subscribeChildChanges(uriParentPath, (parentPath, currentChildren) -> {
            if (CollectionUtils.isNotEmpty(currentChildren)) {
                List<String> addSubscribePath = addSubscribePath(childrenList, currentChildren);
                registerURIChildrenList(addSubscribePath, parentPath);
            }
        });
    }
    
    private void registerURIChildrenList(final List<String> childrenList, final String uriParentPath) {
        List<URIRegisterDTO> registerDTOList = new ArrayList<>();
        childrenList.forEach(addPath -> {
            String realPath = ZkRegisterPathConstants.buildRealNode(uriParentPath, addPath);
            registerDTOList.add(GsonUtils.getInstance().fromJson(zkClient.readData(realPath).toString(), URIRegisterDTO.class));
        });
        publishRegisterURI(registerDTOList);
    }
    
    private void subscribeMetaDataChanges(final String realPath) {
        zkClient.subscribeDataChanges(realPath, new IZkDataListener() {
            @Override
            public void handleDataChange(final String dataPath, final Object data) {
                publishMetadata(data.toString());
            }
        
            @SneakyThrows
            @Override
            public void handleDataDeleted(final String dataPath) {
              
            }
        });
    }
    
    private void publishMetadata(final String data) {
        publisher.publish(Lists.newArrayList(GsonUtils.getInstance().fromJson(data, MetaDataRegisterDTO.class)));
    }
    
    private void publishRegisterURI(final List<URIRegisterDTO> registerDTOList) {
        publisher.publish(registerDTOList);
    }
    
    private List<String> zkClientGetChildren(final String parent) {
        if (!zkClient.exists(parent)) {
            zkClient.createPersistent(parent, true);
        }
        return zkClient.getChildren(parent);
    }
    
    private List<String> addSubscribePath(final List<String> alreadyChildren, final List<String> currentChildren) {
        if (CollectionUtils.isEmpty(alreadyChildren)) {
            return currentChildren;
        }
        return currentChildren.stream().filter(current -> alreadyChildren.stream().noneMatch(current::equals)).collect(Collectors.toList());
    }
}
