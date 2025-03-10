package org.dromara.x.file.storage.core.platform;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.text.NamingCase;
import cn.hutool.core.util.CharUtil;
import cn.hutool.core.util.StrUtil;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.*;
import com.google.cloud.storage.Storage.PredefinedAcl;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageProperties.GoogleCloudStorageConfig;
import org.dromara.x.file.storage.core.ProgressInputStream;
import org.dromara.x.file.storage.core.ProgressListener;
import org.dromara.x.file.storage.core.UploadPretreatment;
import org.dromara.x.file.storage.core.exception.FileStorageRuntimeException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * GoogleCloud Storage 存储
 *
 * @author Kytrun Xuyanwu
 * @version 1.0
 * {@code @date} 2022/11/4 9:56
 */
@Getter
@Setter
@NoArgsConstructor
public class GoogleCloudStorageFileStorage implements FileStorage {
    private String projectId;
    private String bucketName;
    private String credentialsPath;
    private String basePath;
    private String platform;
    private String domain;
    private String defaultAcl;
    private FileStorageClientFactory<Storage> clientFactory;

    public GoogleCloudStorageFileStorage(GoogleCloudStorageConfig config,FileStorageClientFactory<Storage> clientFactory) {
        platform = config.getPlatform();
        bucketName = config.getBucketName();
        domain = config.getDomain();
        basePath = config.getBasePath();
        defaultAcl = config.getDefaultAcl();
        this.clientFactory = clientFactory;
    }

    public Storage getClient() {
        return clientFactory.getClient();
    }


    @Override
    public void close() {
        clientFactory.close();
    }

    public String getFileKey(FileInfo fileInfo) {
        return fileInfo.getBasePath() + fileInfo.getPath() + fileInfo.getFilename();
    }

    public String getThFileKey(FileInfo fileInfo) {
        if (StrUtil.isBlank(fileInfo.getThFilename())) return null;
        return fileInfo.getBasePath() + fileInfo.getPath() + fileInfo.getThFilename();
    }

    @Override
    public boolean save(FileInfo fileInfo,UploadPretreatment pre) {
        fileInfo.setBasePath(basePath);
        String newFileKey = getFileKey(fileInfo);
        fileInfo.setUrl(domain + newFileKey);
        ArrayList<Storage.BlobWriteOption> optionList = new ArrayList<>();
        BlobInfo.Builder blobInfoBuilder = BlobInfo.newBuilder(bucketName,newFileKey);
        setMetadata(blobInfoBuilder,fileInfo,optionList);
        ProgressListener listener = pre.getProgressListener();
        Storage client = getClient();

        try (InputStream in = pre.getFileWrapper().getInputStream()) {
            // 上传原文件
            client.createFrom(blobInfoBuilder.build(),
                    listener == null ? in : new ProgressInputStream(in,listener,fileInfo.getSize()),
                    optionList.toArray(new Storage.BlobWriteOption[]{})
            );

            //上传缩略图
            byte[] thumbnailBytes = pre.getThumbnailBytes();
            if (thumbnailBytes != null) {
                String newThFileKey = getThFileKey(fileInfo);
                fileInfo.setThUrl(domain + newThFileKey);
                ArrayList<Storage.BlobWriteOption> thOptionList = new ArrayList<>();
                BlobInfo.Builder thBlobInfoBuilder = BlobInfo.newBuilder(bucketName,newThFileKey);
                setThMetadata(thBlobInfoBuilder,fileInfo,thOptionList);
                client.createFrom(thBlobInfoBuilder.build(),new ByteArrayInputStream(thumbnailBytes),thOptionList.toArray(new Storage.BlobWriteOption[]{}));
            }
            return true;
        } catch (IOException e) {
            checkAndDelete(newFileKey);
            throw new FileStorageRuntimeException("文件上传失败！platform：" + platform + "，filename：" + fileInfo.getOriginalFilename(),e);
        }
    }

    /**
     * 获取文件的访问控制列表，这里又分为 PredefinedAcl 和 List<ACL>
     */
    public AclWrapper getAcl(Object acl) {
        if (acl instanceof PredefinedAcl) {
            return new AclWrapper((PredefinedAcl) acl);
        } else if (acl instanceof String || acl == null) {
            String sAcl = (String) acl;
            if (StrUtil.isEmpty(sAcl)) sAcl = defaultAcl;
            if (StrUtil.isEmpty(sAcl)) return null;
            sAcl = sAcl.replace("-","_");
            for (PredefinedAcl item : PredefinedAcl.values()) {
                if (item.toString().equalsIgnoreCase(sAcl)) {
                    return new AclWrapper(item);
                }
            }
            return null;
        } else if (acl instanceof Acl) {
            return new AclWrapper(Collections.singletonList((Acl) acl));
        } else if (acl instanceof Collection) {
            List<Acl> aclList = ((Collection<?>) acl).stream().map(item -> {
                if (item instanceof Acl) {
                    return (Acl) item;
                } else {
                    throw new FileStorageRuntimeException("不支持的ACL：" + item);
                }
            }).collect(Collectors.toList());
            return new AclWrapper(aclList);
        } else {
            throw new FileStorageRuntimeException("不支持的ACL：" + acl);
        }
    }

    /**
     * 设置对象的元数据
     */
    public void setMetadata(BlobInfo.Builder blobInfoBuilder,FileInfo fileInfo,ArrayList<Storage.BlobWriteOption> optionList) {
        blobInfoBuilder.setContentType(fileInfo.getContentType()).setMetadata(fileInfo.getUserMetadata());
        if (CollUtil.isNotEmpty(fileInfo.getMetadata())) {
            CopyOptions copyOptions = CopyOptions.create().ignoreCase().setFieldNameEditor(name -> NamingCase.toCamelCase(name,CharUtil.DASHED));
            BeanUtil.copyProperties(fileInfo.getMetadata(),blobInfoBuilder,copyOptions);
        }
        AclWrapper fileAcl = getAcl(fileInfo.getFileAcl());
        if (fileAcl != null) {
            if (fileAcl.getAclList() != null) {
                blobInfoBuilder.setAcl(fileAcl.getAclList());
            } else if (fileAcl.getPredefinedAcl() != null) {
                optionList.add(Storage.BlobWriteOption.predefinedAcl(fileAcl.getPredefinedAcl()));
            }
        }
    }

    /**
     * 设置缩略图对象的元数据
     */
    public void setThMetadata(BlobInfo.Builder blobInfoBuilder,FileInfo fileInfo,ArrayList<Storage.BlobWriteOption> optionList) {
        blobInfoBuilder.setContentType(fileInfo.getThContentType()).setMetadata(fileInfo.getThUserMetadata());
        if (CollUtil.isNotEmpty(fileInfo.getThMetadata())) {
            CopyOptions copyOptions = CopyOptions.create().ignoreCase().setFieldNameEditor(name -> NamingCase.toCamelCase(name,CharUtil.DASHED));
            BeanUtil.copyProperties(fileInfo.getThMetadata(),blobInfoBuilder,copyOptions);
        }
        AclWrapper fileAcl = getAcl(fileInfo.getThFileAcl());
        if (fileAcl != null) {
            if (fileAcl.getAclList() != null) {
                blobInfoBuilder.setAcl(fileAcl.getAclList());
            } else if (fileAcl.getPredefinedAcl() != null) {
                optionList.add(Storage.BlobWriteOption.predefinedAcl(fileAcl.getPredefinedAcl()));
            }
        }
    }

    @Override
    public boolean isSupportAcl() {
        return true;
    }

    @Override
    public boolean setFileAcl(FileInfo fileInfo,Object acl) {
        AclWrapper oAcl = getAcl(acl);
        if (oAcl == null) return false;
        BlobInfo.Builder builder = BlobInfo.newBuilder(bucketName,getFileKey(fileInfo));
        if (oAcl.getAclList() != null) {
            builder.setAcl(oAcl.getAclList());
            getClient().update(builder.build());
            return true;
        } else if (oAcl.getPredefinedAcl() != null) {
            getClient().update(builder.build(),Storage.BlobTargetOption.predefinedAcl(oAcl.getPredefinedAcl()));
            return true;
        }
        return false;
    }

    @Override
    public boolean setThFileAcl(FileInfo fileInfo,Object acl) {
        AclWrapper oAcl = getAcl(acl);
        if (oAcl == null) return false;
        BlobInfo.Builder builder = BlobInfo.newBuilder(bucketName,getThFileKey(fileInfo));
        if (oAcl.getAclList() != null) {
            builder.setAcl(oAcl.getAclList());
            getClient().update(builder.build());
            return true;
        } else if (oAcl.getPredefinedAcl() != null) {
            getClient().update(builder.build(),Storage.BlobTargetOption.predefinedAcl(oAcl.getPredefinedAcl()));
            return true;
        }
        return false;
    }

    @Override
    public boolean isSupportPresignedUrl() {
        return true;
    }

    @Override
    public String generatePresignedUrl(FileInfo fileInfo,Date expiration) {
        BlobInfo blobInfo = BlobInfo.newBuilder(bucketName,getFileKey(fileInfo)).build();
        long duration = expiration.getTime() - System.currentTimeMillis();
        return getClient().signUrl(blobInfo,duration,TimeUnit.MILLISECONDS).toString();
    }

    @Override
    public String generateThPresignedUrl(FileInfo fileInfo,Date expiration) {
        BlobInfo blobInfo = BlobInfo.newBuilder(bucketName,getThFileKey(fileInfo)).build();
        long duration = expiration.getTime() - System.currentTimeMillis();
        return getClient().signUrl(blobInfo,duration,TimeUnit.MILLISECONDS).toString();
    }

    @Override
    public boolean isSupportMetadata() {
        return true;
    }

    /**
     * 检查并删除对象
     * <a href="https://github.com/googleapis/java-storage/blob/main/samples/snippets/src/main/java/com/example/storage/object/DeleteObject.java">Source Example</a>
     *
     * @param fileKey 对象 key
     */
    protected void checkAndDelete(String fileKey) {
        Storage client = getClient();
        Blob blob = client.get(bucketName,fileKey);
        if (blob != null) {
            Storage.BlobSourceOption precondition = Storage.BlobSourceOption.generationMatch(blob.getGeneration());
            client.delete(bucketName,fileKey,precondition);
        }
    }

    @Override
    public boolean delete(FileInfo fileInfo) {
        //删除缩略图
        if (fileInfo.getThFilename() != null) {
            checkAndDelete(getThFileKey(fileInfo));
        }
        checkAndDelete(getFileKey(fileInfo));
        return true;
    }

    @Override
    public boolean exists(FileInfo fileInfo) {
        Storage client = getClient();
        BlobId blobId = BlobId.of(bucketName,getFileKey(fileInfo));
        return client.get(blobId) != null;
    }

    @Override
    public void download(FileInfo fileInfo,Consumer<InputStream> consumer) {
        Storage client = getClient();
        BlobId blobId = BlobId.of(bucketName,getFileKey(fileInfo));

        try (ReadChannel readChannel = client.reader(blobId);
             InputStream in = Channels.newInputStream(readChannel)) {
            consumer.accept(in);
        } catch (IOException e) {
            throw new FileStorageRuntimeException("文件下载失败！fileInfo：" + fileInfo,e);
        }

    }

    @Override
    public void downloadTh(FileInfo fileInfo,Consumer<InputStream> consumer) {
        if (StrUtil.isBlank(fileInfo.getThFilename())) {
            throw new FileStorageRuntimeException("缩略图文件下载失败，文件不存在！fileInfo：" + fileInfo);
        }
        Storage client = getClient();
        BlobId thBlobId = BlobId.of(bucketName,getThFileKey(fileInfo));
        try (ReadChannel readChannel = client.reader(thBlobId);
             InputStream in = Channels.newInputStream(readChannel)) {
            consumer.accept(in);
        } catch (IOException e) {
            throw new FileStorageRuntimeException("缩略图文件下载失败！fileInfo：" + fileInfo,e);
        }
    }

    @Data
    public static class AclWrapper {
        private List<Acl> aclList;
        private PredefinedAcl predefinedAcl;

        public AclWrapper(List<Acl> aclList) {
            this.aclList = aclList;
        }

        public AclWrapper(PredefinedAcl predefinedAcl) {
            this.predefinedAcl = predefinedAcl;
        }
    }
}
