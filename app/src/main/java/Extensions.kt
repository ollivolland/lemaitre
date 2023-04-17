import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.R

fun Spinner.config(headers: Array<String>, selectionIndex:Int, onSelect: (Int) -> Unit) {
    this.adapter = ArrayAdapter(this.context, R.layout.support_simple_spinner_dropdown_item, headers)
    this.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit

        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            onSelect(position)
        }
    }
    this.setSelection(selectionIndex)
}