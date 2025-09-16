package com.v2ray.ang.dto

data class Country(
    val code: String,          // 国家代码 (jp, us, jj等)
    val name: String,          // 国家名称
    val flagRes: Int,          // 国旗资源ID
    var isSelected: Boolean = false  // 是否被高亮选中
)