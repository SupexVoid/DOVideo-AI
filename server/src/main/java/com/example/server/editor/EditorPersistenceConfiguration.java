package com.example.server.editor;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan(basePackageClasses=EditorMapper.class, annotationClass=Mapper.class)
public class EditorPersistenceConfiguration {}
