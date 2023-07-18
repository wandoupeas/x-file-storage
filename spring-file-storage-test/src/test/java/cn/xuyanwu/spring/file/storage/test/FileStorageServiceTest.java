package cn.xuyanwu.spring.file.storage.test;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.xuyanwu.spring.file.storage.FileInfo;
import cn.xuyanwu.spring.file.storage.FileStorageService;
import cn.xuyanwu.spring.file.storage.ProgressListener;
import cn.xuyanwu.spring.file.storage.constant.Constant;
import cn.xuyanwu.spring.file.storage.platform.FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Date;


@Slf4j
@SpringBootTest
class FileStorageServiceTest {

    @Autowired
    private FileStorageService fileStorageService;

    /**
     * 单独对文件上传进行测试
     */
    @Test
    public void upload() {

        String filename = "image.jpg";
        InputStream in = this.getClass().getClassLoader().getResourceAsStream(filename);

        FileInfo fileInfo = fileStorageService.of(in)
                .setName("file")
                .setOriginalFilename(filename)
                .setPath("test/")
                .thumbnail()
                .putAttr("role","admin")
                .setAcl(Constant.ACL.PRIVATE)
                .setProgressMonitor(new ProgressListener() {
                    @Override
                    public void start() {
                        System.out.println("上传开始");
                    }

                    @Override
                    public void progress(long progressSize,long allSize) {
                        System.out.println("已上传 " + progressSize + " 总大小" + allSize + " " + (progressSize * 10000 / allSize * 0.01) + "%");
                    }

                    @Override
                    public void finish() {
                        System.out.println("上传结束");
                    }
                })
                .upload();
        Assert.notNull(fileInfo,"文件上传失败！");
        log.info("文件上传成功：{}",fileInfo.toString());

        String presignedUrl = fileStorageService.generatePresignedUrl(fileInfo,DateUtil.offsetHour(new Date(),1));
        System.out.println("文件授权访问地址：" + presignedUrl);

        String thPresignedUrl = fileStorageService.generateThPresignedUrl(fileInfo,DateUtil.offsetHour(new Date(),1));
        System.out.println("缩略图文件授权访问地址：" + thPresignedUrl);

//        fileStorageService.setFileAcl(fileInfo,Constant.ACL.PUBLIC_READ);
//        fileStorageService.setThFileAcl(fileInfo,Constant.ACL.PUBLIC_READ);
    }

    /**
     * 测试根据 url 上传文件
     */
    @Test
    public void uploadByURL() {

        String url = "https://www.xuyanwu.cn/file/upload/1566046282790-1.png";

        FileInfo fileInfo = fileStorageService.of(url).thumbnail().setPath("test/").setObjectId("0").setObjectType("0").upload();
        Assert.notNull(fileInfo,"文件上传失败！");
        log.info("文件上传成功：{}",fileInfo.toString());
    }

    /**
     * 测试大文件上传
     */
    @Test
    public void uploadBigFile() throws IOException {
        String url = "https://app.xuyanwu.cn/BadApple/video/Bad%20Apple.mp4";

        File file = new File(System.getProperty("java.io.tmpdir"),"Bad Apple.mp4");
        if (!file.exists()) {
            log.info("测试大文件不存在，正在下载中");
            FileUtil.writeFromStream(new URL(url).openStream(),file);
            log.info("测试大文件下载完成");
        }

        FileInfo fileInfo = fileStorageService.of(file).setPath("test/").setObjectId("0").setObjectType("0").upload();
        Assert.notNull(fileInfo,"大文件上传失败！");
        log.info("大文件上传成功：{}",fileInfo.toString());
    }

    /**
     * 测试上传并删除文件
     */
    @Test
    public void delete() {
        String filename = "image.jpg";
        InputStream in = this.getClass().getClassLoader().getResourceAsStream(filename);

        FileInfo fileInfo = fileStorageService.of(in).setOriginalFilename(filename).setPath("test/").setObjectId("0").setObjectType("0").putAttr("role","admin").thumbnail(200,200).upload();
        Assert.notNull(fileInfo,"文件上传失败！");

        log.info("尝试删除已存在的文件：{}",fileInfo);
        boolean delete = fileStorageService.delete(fileInfo.getUrl());
        Assert.isTrue(delete,"文件删除失败！" + fileInfo.getUrl());
        log.info("文件删除成功：{}",fileInfo);

        fileInfo = BeanUtil.copyProperties(fileInfo,FileInfo.class);
        fileInfo.setFilename(fileInfo.getFilename() + "111.tmp");
        fileInfo.setUrl(fileInfo.getUrl() + "111.tmp");
        log.info("尝试删除不存在的文件：{}",fileInfo);
        delete = fileStorageService.delete(fileInfo);
        Assert.isTrue(delete,"文件删除失败！" + fileInfo.getUrl());
        log.info("文件删除成功：{}",fileInfo);
    }

    /**
     * 测试上传并验证文件是否存在
     */
    @Test
    public void exists() {
        String filename = "image.jpg";
        InputStream in = this.getClass().getClassLoader().getResourceAsStream(filename);
        FileInfo fileInfo = fileStorageService.of(in).setOriginalFilename(filename).setPath("test/").setObjectId("0").setObjectType("0").upload();
        Assert.notNull(fileInfo,"文件上传失败！");
        boolean exists = fileStorageService.exists(fileInfo);
        log.info("文件是否存在，应该存在，实际为：{}，文件：{}",exists,fileInfo);
        Assert.isTrue(exists,"文件是否存在，应该存在，实际为：{}，文件：{}",exists,fileInfo);

        fileInfo = BeanUtil.copyProperties(fileInfo,FileInfo.class);
        fileInfo.setFilename(fileInfo.getFilename() + "111.cc");
        fileInfo.setUrl(fileInfo.getUrl() + "111.cc");
        exists = fileStorageService.exists(fileInfo);
        log.info("文件是否存在，不该存在，实际为：{}，文件：{}",exists,fileInfo);
        Assert.isFalse(exists,"文件是否存在，不该存在，实际为：{}，文件：{}",exists,fileInfo);
    }


    /**
     * 测试上传并下载文件
     */
    @Test
    public void download() {
        String filename = "image.jpg";
        InputStream in = this.getClass().getClassLoader().getResourceAsStream(filename);

        FileInfo fileInfo = fileStorageService.of(in).setOriginalFilename(filename).setPath("test/").setObjectId("0").setObjectType("0").setSaveFilename("aaa.jpg").setSaveThFilename("bbb").thumbnail(200,200).upload();
        Assert.notNull(fileInfo,"文件上传失败！");

        byte[] bytes = fileStorageService.download(fileInfo).setProgressMonitor((progressSize,allSize) ->
                log.info("文件下载进度：{} {}%",progressSize,progressSize * 100 / allSize)
        ).bytes();
        Assert.notNull(bytes,"文件下载失败！");
        log.info("文件下载成功，文件大小：{}",bytes.length);

        byte[] thBytes = fileStorageService.downloadTh(fileInfo).setProgressMonitor((progressSize,allSize) ->
                log.info("缩略图文件下载进度：{} {}%",progressSize,progressSize * 100 / allSize)
        ).bytes();
        Assert.notNull(thBytes,"缩略图文件下载失败！");
        log.info("缩略图文件下载成功，文件大小：{}",thBytes.length);


    }

    /**
     * 测试通过反射调用存储平台的方法
     */
    @Test
    public void invoke() {
        FileStorage fileStorage = fileStorageService.getFileStorage();
        Object[] args = new Object[]{fileStorage.getPlatform()};
        Object exists = fileStorageService.invoke(fileStorage,"setPlatform",args);
        log.info("通过反射调用存储平台的方法（文件是否存在）成功，结果：{}",exists);
    }

}
