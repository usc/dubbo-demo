package com.github.usc.dubbo.demo.generic.api;

public interface IService <P, V> {
    V get(P params);
}
