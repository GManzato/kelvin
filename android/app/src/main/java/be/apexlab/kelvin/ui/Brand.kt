package be.apexlab.kelvin.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import be.apexlab.kelvin.R

/** Exact C3 sensor-grid symbol and lowercase wordmark from the Kelvin logo artifact. */
@Composable
fun KelvinBrand(modifier: Modifier = Modifier, height: Dp = 28.dp) {
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Image(
            painter = painterResource(R.drawable.kelvin_brand),
            contentDescription = "Kelvin",
            modifier = Modifier.height(height),
        )
    }
}
