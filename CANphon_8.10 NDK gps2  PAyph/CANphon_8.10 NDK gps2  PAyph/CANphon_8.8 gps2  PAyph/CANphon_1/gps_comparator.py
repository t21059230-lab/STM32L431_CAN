"""
📍 GPS Comparator - Auto-filters (0,0) points
"""

import tkinter as tk
from tkinter import filedialog, messagebox, ttk
import csv
import os

try:
    import matplotlib.pyplot as plt
    from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg, NavigationToolbar2Tk
    HAS_MATPLOTLIB = True
except:
    HAS_MATPLOTLIB = False

class GPSComparator:
    def __init__(self, root):
        self.root = root
        self.root.title("📍 GPS Comparator")
        self.root.geometry("1200x800")
        self.root.configure(bg='#1e1e2e')
        
        self.phone_data = None
        self.external_data = None
        
        self.setup_ui()
        
    def setup_ui(self):
        tk.Label(self.root, text="📍 GPS Track Comparator", 
                 font=('Arial', 22, 'bold'), fg='#4ecca3', bg='#1e1e2e').pack(pady=10)
        
        # Settings
        settings = tk.LabelFrame(self.root, text="⚙️ Column Settings (0=A, 1=B, 2=C...)", 
                                 bg='#2d2d44', fg='#4ecca3', font=('Arial', 11, 'bold'))
        settings.pack(fill=tk.X, padx=15, pady=5)
        
        # Phone
        pf = tk.Frame(settings, bg='#2d2d44')
        pf.pack(side=tk.LEFT, padx=20, pady=5)
        tk.Label(pf, text="📱 Phone: Lat=", fg='#ff6b6b', bg='#2d2d44').pack(side=tk.LEFT)
        self.phone_lat = tk.StringVar(value="1")
        tk.Entry(pf, textvariable=self.phone_lat, width=3).pack(side=tk.LEFT)
        tk.Label(pf, text=" Lon=", fg='#ff6b6b', bg='#2d2d44').pack(side=tk.LEFT)
        self.phone_lon = tk.StringVar(value="2")
        tk.Entry(pf, textvariable=self.phone_lon, width=3).pack(side=tk.LEFT)
        
        self.phone_header = tk.BooleanVar(value=True)  # Default ON
        tk.Checkbutton(pf, text="Skip header", variable=self.phone_header, 
                       bg='#2d2d44', fg='white', selectcolor='#1e1e2e').pack(side=tk.LEFT, padx=10)

        # External
        ef = tk.Frame(settings, bg='#2d2d44')
        ef.pack(side=tk.LEFT, padx=20, pady=5)
        tk.Label(ef, text="📡 External: Lat=", fg='#74b9ff', bg='#2d2d44').pack(side=tk.LEFT)
        self.ext_lat = tk.StringVar(value="1")
        tk.Entry(ef, textvariable=self.ext_lat, width=3).pack(side=tk.LEFT)
        tk.Label(ef, text=" Lon=", fg='#74b9ff', bg='#2d2d44').pack(side=tk.LEFT)
        self.ext_lon = tk.StringVar(value="2")
        tk.Entry(ef, textvariable=self.ext_lon, width=3).pack(side=tk.LEFT)
        
        self.ext_header = tk.BooleanVar(value=True)
        tk.Checkbutton(ef, text="Skip header", variable=self.ext_header,
                       bg='#2d2d44', fg='white', selectcolor='#1e1e2e').pack(side=tk.LEFT, padx=10)
        
        # Buttons
        bf = tk.Frame(self.root, bg='#1e1e2e')
        bf.pack(pady=15)
        
        tk.Button(bf, text="📱 Phone GPS\n(Red 🔴)", command=self.load_phone,
                  font=('Arial', 12, 'bold'), bg='#e74c3c', fg='white', 
                  width=16, height=2).pack(side=tk.LEFT, padx=8)
        
        tk.Button(bf, text="📡 External GPS\n(Blue 🔵)", command=self.load_external,
                  font=('Arial', 12, 'bold'), bg='#3498db', fg='white',
                  width=16, height=2).pack(side=tk.LEFT, padx=8)
        
        tk.Button(bf, text="📊 DRAW\nارسم", command=self.plot,
                  font=('Arial', 12, 'bold'), bg='#27ae60', fg='white',
                  width=16, height=2).pack(side=tk.LEFT, padx=8)
        
        tk.Button(bf, text="💾 Save\nحفظ", command=self.save,
                  font=('Arial', 12, 'bold'), bg='#9b59b6', fg='white',
                  width=16, height=2).pack(side=tk.LEFT, padx=8)
        
        # Status
        self.status = tk.Label(self.root, text="📌 Auto-filters (0,0) and invalid points", 
                               font=('Arial', 11), fg='#f1c40f', bg='#1e1e2e')
        self.status.pack(pady=5)
        
        # Preview
        self.preview = tk.Text(self.root, height=6, bg='#0d0d15', fg='#4ecca3',
                               font=('Consolas', 9))
        self.preview.pack(fill=tk.X, padx=15)
        
        # Plot
        if HAS_MATPLOTLIB:
            pf = tk.Frame(self.root, bg='#0d0d15')
            pf.pack(fill=tk.BOTH, expand=True, padx=15, pady=10)
            
            self.fig, self.ax = plt.subplots(figsize=(12, 6), facecolor='#0d0d15')
            self.ax.set_facecolor('#1a1a2e')
            
            self.canvas = FigureCanvasTkAgg(self.fig, master=pf)
            self.canvas.get_tk_widget().pack(fill=tk.BOTH, expand=True)
            
            tf = tk.Frame(pf, bg='#1e1e2e')
            tf.pack(fill=tk.X)
            NavigationToolbar2Tk(self.canvas, tf)
            
            self.init_plot()
    
    def init_plot(self):
        self.ax.clear()
        self.ax.set_xlabel('Longitude', color='white')
        self.ax.set_ylabel('Latitude', color='white')
        self.ax.set_title('GPS Path Comparison', color='#4ecca3')
        self.ax.tick_params(colors='white')
        self.ax.grid(True, alpha=0.3)
        self.canvas.draw()
    
    def read_csv(self, path, lat_col, lon_col, skip_first):
        """Read CSV and filter out (0,0) and invalid points"""
        lats, lons = [], []
        raw_rows = []
        skipped_zero = 0
        skipped_invalid = 0
        
        with open(path, 'r', encoding='utf-8', errors='ignore') as f:
            content = f.read()
        
        # Detect delimiter
        if content.count('\t') > content.count(','):
            delim = '\t'
        elif content.count(';') > content.count(','):
            delim = ';'
        else:
            delim = ','
        
        lines = content.strip().split('\n')
        
        for i, line in enumerate(lines):
            parts = line.split(delim)
            
            if i < 3:
                raw_rows.append(str(parts[:7]))
            
            if skip_first and i == 0:
                continue
            
            if len(parts) > max(lat_col, lon_col):
                try:
                    lat_str = parts[lat_col].strip().replace(',', '.')
                    lon_str = parts[lon_col].strip().replace(',', '.')
                    
                    lat = float(lat_str)
                    lon = float(lon_str)
                    
                    # ========== FILTER ==========
                    # Skip (0,0) points - GPS not initialized
                    if abs(lat) < 0.001 and abs(lon) < 0.001:
                        skipped_zero += 1
                        continue
                    
                    # Skip obviously invalid coordinates
                    if lat < -90 or lat > 90 or lon < -180 or lon > 180:
                        skipped_invalid += 1
                        continue
                    
                    # Skip if lat or lon is exactly 0 (likely error)
                    if lat == 0 or lon == 0:
                        skipped_zero += 1
                        continue
                    
                    lats.append(lat)
                    lons.append(lon)
                except:
                    pass
        
        return lats, lons, raw_rows, skipped_zero, skipped_invalid
    
    def show_preview(self, title, lats, lons, raw_rows, skipped_zero, skipped_invalid):
        self.preview.delete('1.0', tk.END)
        text = f"{title}\n"
        text += f"✅ Valid points: {len(lats)}\n"
        text += f"⚠️ Skipped (0,0): {skipped_zero} | Invalid: {skipped_invalid}\n"
        if lats:
            text += f"First: Lat={lats[0]:.6f}, Lon={lons[0]:.6f}\n"
            text += f"Last:  Lat={lats[-1]:.6f}, Lon={lons[-1]:.6f}\n"
        text += f"Header: {raw_rows[0] if raw_rows else 'N/A'}"
        self.preview.insert('1.0', text)
    
    def load_phone(self):
        path = filedialog.askopenfilename(
            filetypes=[("CSV", "*.csv"), ("All", "*.*")])
        if not path:
            return
        try:
            lat_col = int(self.phone_lat.get())
            lon_col = int(self.phone_lon.get())
            skip = self.phone_header.get()
            
            lats, lons, raw, skipped_z, skipped_i = self.read_csv(path, lat_col, lon_col, skip)
            
            if len(lats) == 0:
                raise Exception("No valid GPS points! All were (0,0) or invalid.")
            
            self.phone_data = {'lat': lats, 'lon': lons}
            self.show_preview("📱 PHONE GPS", lats, lons, raw, skipped_z, skipped_i)
            self.status.config(text=f"✅ Phone: {len(lats)} points (filtered)", fg='#4ecca3')
        except Exception as e:
            messagebox.showerror("Error", str(e))
    
    def load_external(self):
        path = filedialog.askopenfilename(
            filetypes=[("CSV", "*.csv"), ("All", "*.*")])
        if not path:
            return
        try:
            lat_col = int(self.ext_lat.get())
            lon_col = int(self.ext_lon.get())
            skip = self.ext_header.get()
            
            lats, lons, raw, skipped_z, skipped_i = self.read_csv(path, lat_col, lon_col, skip)
            
            if len(lats) == 0:
                raise Exception("No valid GPS points!")
            
            self.external_data = {'lat': lats, 'lon': lons}
            self.show_preview("📡 EXTERNAL GPS", lats, lons, raw, skipped_z, skipped_i)
            self.status.config(text=f"✅ External: {len(lats)} points (filtered)", fg='#4ecca3')
        except Exception as e:
            messagebox.showerror("Error", str(e))
    
    def plot(self):
        if not self.phone_data and not self.external_data:
            messagebox.showinfo("Info", "Load GPS data first!")
            return
        
        self.ax.clear()
        
        if self.phone_data:
            self.ax.plot(self.phone_data['lon'], self.phone_data['lat'], 
                        'r-', lw=2, alpha=0.9, label=f"📱 Phone ({len(self.phone_data['lat'])})")
            # Start marker
            self.ax.scatter([self.phone_data['lon'][0]], [self.phone_data['lat'][0]], 
                           c='lime', s=150, marker='^', zorder=10, edgecolors='black', label='Start')
            # End marker
            self.ax.scatter([self.phone_data['lon'][-1]], [self.phone_data['lat'][-1]], 
                           c='red', s=150, marker='s', zorder=10, edgecolors='black', label='End')
        
        if self.external_data:
            self.ax.plot(self.external_data['lon'], self.external_data['lat'],
                        'b-', lw=2, alpha=0.9, label=f"📡 External ({len(self.external_data['lat'])})")
            if not self.phone_data:  # Only show markers if no phone data
                self.ax.scatter([self.external_data['lon'][0]], [self.external_data['lat'][0]],
                               c='cyan', s=150, marker='^', zorder=10, edgecolors='black')
        
        self.ax.set_xlabel('Longitude (خط الطول)', color='white', fontsize=11)
        self.ax.set_ylabel('Latitude (خط العرض)', color='white', fontsize=11)
        self.ax.set_title('🗺️ GPS Path Comparison - مقارنة المسارات', color='#4ecca3', fontsize=14)
        self.ax.tick_params(colors='white')
        self.ax.grid(True, alpha=0.3, linestyle='--')
        self.ax.legend(facecolor='#1a1a2e', labelcolor='white', loc='best')
        self.ax.set_aspect('equal')
        
        # Add some padding
        self.fig.tight_layout()
        self.canvas.draw()
        self.status.config(text="✅ Map drawn! Use toolbar to zoom/pan", fg='#4ecca3')
    
    def save(self):
        path = filedialog.asksaveasfilename(
            defaultextension=".png",
            initialfile="gps_comparison.png",
            filetypes=[("PNG", "*.png"), ("PDF", "*.pdf")])
        if path:
            self.fig.savefig(path, dpi=300, facecolor='#0d0d15', bbox_inches='tight')
            messagebox.showinfo("✅ Saved!", f"Saved to:\n{path}")

def main():
    root = tk.Tk()
    GPSComparator(root)
    root.mainloop()

if __name__ == "__main__":
    main()
