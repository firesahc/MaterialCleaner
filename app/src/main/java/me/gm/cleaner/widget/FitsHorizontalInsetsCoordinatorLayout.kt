/*
 * Copyright 2023 Green Mushroom
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.gm.cleaner.widget

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.WindowInsets
import androidx.annotation.AttrRes
import androidx.coordinatorlayout.widget.CoordinatorLayout

class FitsHorizontalInsetsCoordinatorLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, @AttrRes defStyleAttr: Int = 0
) : CoordinatorLayout(context, attrs, defStyleAttr) {
    private val mPaddingLeft: Int = paddingLeft
    private val mPaddingTop: Int = paddingTop
    private val mPaddingRight: Int = paddingRight
    private val mPaddingBottom: Int = paddingBottom

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // 使用现代 API 获取系统栏水平边距
        val leftInset: Int
        val rightInset: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val systemBars = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            leftInset = systemBars.left
            rightInset = systemBars.right
        } else {
            @Suppress("DEPRECATION")
            leftInset = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            rightInset = insets.systemWindowInsetRight
        }
        setPadding(
            mPaddingLeft + leftInset, mPaddingTop,
            mPaddingRight + rightInset, mPaddingBottom
        )
        // 不消费 insets，让子 View(包括 ViewPager 内的 Fragment) 也能接收到完整边距
        return insets
    }
}
