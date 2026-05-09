package com.example.ink2text

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.ink2text.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.fabProcessImage.setOnClickListener {
            showImageSourceDialog()
        }
    }

    private fun showImageSourceDialog() {
        val options = arrayOf("Camera", "Album")
        AlertDialog.Builder(this)
            .setTitle("Choose Image Source")
            .setItems(options) { dialog, which ->
                val intent = Intent(this, MainActivity::class.java)
                if (which == 0) {
                    intent.putExtra("IMAGE_SOURCE", "CAMERA")
                } else {
                    intent.putExtra("IMAGE_SOURCE", "ALBUM")
                }
                startActivity(intent)
            }
            .show()
    }
}
