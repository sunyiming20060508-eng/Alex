package com.linli.common;

import java.util.List;

/** 目标分页结构：{ "list": [], "total": 0, "page": 1, "pageSize": 10 } */
public record PageResult<T>(List<T> list, long total, int page, int pageSize) {}
