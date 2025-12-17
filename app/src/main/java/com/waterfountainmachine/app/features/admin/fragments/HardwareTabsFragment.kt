package com.waterfountainmachine.app.admin.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.databinding.FragmentHardwareTabsBinding

/**
 * Hardware Tabs Fragment
 * 
 * Container fragment that hosts hardware debugging panels in tabs:
 * - Connection: Hardware connection status and controls
 * - Testing: Hardware testing (48 slots) and fault clearing
 * - Slot Inventory: Visual slot inventory grid with backend sync
 */
class HardwareTabsFragment : Fragment() {
    
    private var _binding: FragmentHardwareTabsBinding? = null
    private val binding get() = _binding!!
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHardwareTabsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupTabs()
    }
    
    private fun setupTabs() {
        val adapter = HardwarePagerAdapter(this)
        binding.viewPager.adapter = adapter
        
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Connection"
                1 -> "Testing"
                2 -> "Slot Inventory"
                else -> ""
            }
        }.attach()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    private inner class HardwarePagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        
        override fun getItemCount(): Int = 3
        
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> HardwareConnectionFragment()
                1 -> HardwareTestingFragment()
                2 -> SlotInventoryFragment()
                else -> HardwareConnectionFragment()
            }
        }
    }
}
