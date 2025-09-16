package com.v2ray.ang.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemCountrySelectorBinding
import com.v2ray.ang.dto.Country

class CountrySelectorAdapter : RecyclerView.Adapter<CountrySelectorAdapter.CountryViewHolder>() {

    private var countries = listOf<Country>()
    private var listener: OnCountryClickListener? = null
    var isSwitching = false  // 换区状态标记，用于禁用点击

    /**
     * 国家点击监听器接口
     */
    interface OnCountryClickListener {
        fun onCountryClick(countryCode: String)
    }

    /**
     * 设置点击监听器
     */
    fun setOnCountryClickListener(listener: OnCountryClickListener?) {
        this.listener = listener
    }

    /**
     * 初始化国家列表，需要Context来获取字符串资源
     */
    fun initializeCountries(context: Context) {
        countries = listOf(
            Country("jp", context.getString(R.string.country_jp), R.drawable.jp),    // 注意：jp = 韩国
            Country("us", context.getString(R.string.country_us), R.drawable.us),
            Country("jj", context.getString(R.string.country_jj), R.drawable.jj),    // 注意：jj = 日本
            Country("in", context.getString(R.string.country_in), R.drawable.`in`),
            Country("si", context.getString(R.string.country_si), R.drawable.si),
            Country("au", context.getString(R.string.country_au), R.drawable.au),
            Country("ca", context.getString(R.string.country_ca), R.drawable.ca),
            Country("ge", context.getString(R.string.country_ge), R.drawable.ge),
            Country("ir", context.getString(R.string.country_ir), R.drawable.ir),
            Country("ki", context.getString(R.string.country_ki), R.drawable.ki),
            Country("fr", context.getString(R.string.country_fr), R.drawable.fr),
            Country("sw", context.getString(R.string.country_sw), R.drawable.sw)
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CountryViewHolder {
        val binding = ItemCountrySelectorBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CountryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CountryViewHolder, position: Int) {
        holder.bind(countries[position])
    }

    override fun getItemCount() = countries.size

    /**
     * 根据国家代码设置高亮选中状态
     * @param countryCode v2rayurl中解析出的国家代码
     */
    fun setSelectedCountry(countryCode: String) {
        // 清除之前的选中状态
        countries.forEach { it.isSelected = false }

        // 设置新的选中状态
        countries.find { it.code == countryCode }?.let { country ->
            country.isSelected = true
        }

        // 刷新列表显示
        notifyDataSetChanged()
    }

    inner class CountryViewHolder(private val binding: ItemCountrySelectorBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(country: Country) {
            binding.flagImage.setImageResource(country.flagRes)
            binding.countryName.text = country.name

            // 根据选中状态设置背景色和文字颜色
            if (country.isSelected) {
                // 选中状态：蓝色背景，白色文字
                binding.countryItemBg.setBackgroundColor(
                    ContextCompat.getColor(binding.root.context, R.color.colorAccent)
                )
                binding.countryName.setTextColor(
                    ContextCompat.getColor(binding.root.context, android.R.color.white)
                )
            } else {
                // 未选中状态：透明背景，默认文字颜色
                binding.countryItemBg.setBackgroundColor(
                    ContextCompat.getColor(binding.root.context, android.R.color.transparent)
                )
                binding.countryName.setTextColor(
                    ContextCompat.getColor(binding.root.context, android.R.color.black)
                )
            }

            // 设置点击事件
            binding.countryItemBg.setOnClickListener {
                // 只有在非换区状态且不是当前选中的国家时才允许点击
                if (!isSwitching && !country.isSelected) {
                    listener?.onCountryClick(country.code)
                }
            }

            // 根据换区状态设置透明度（换区时降低透明度表示禁用）
            binding.countryItemBg.alpha = if (isSwitching) 0.5f else 1.0f
        }
    }
}