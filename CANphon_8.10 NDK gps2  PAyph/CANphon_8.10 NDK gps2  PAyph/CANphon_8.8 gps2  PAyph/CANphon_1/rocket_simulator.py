"""
محاكاة بصرية لصاروخ علمي
Visual Scientific Rocket Simulator
"""

import matplotlib.pyplot as plt
import matplotlib.patches as patches
from matplotlib.animation import FuncAnimation
import numpy as np
import math

class VisualRocketSimulator:
    def __init__(self):
        # إعدادات الصاروخ
        self.mass = 3.0  # كجم
        self.thrust = 150.0  # نيوتن
        self.burn_time = 3.0  # ثواني
        self.drag_coef = 0.4
        
        # الحالة
        self.x = 50.0
        self.y = 0.0
        self.vx = 0.0
        self.vy = 0.0
        self.angle = 80  # درجة
        self.time = 0.0
        self.dt = 0.02
        
        # السجل
        self.trail_x = []
        self.trail_y = []
        self.max_alt = 0
        self.phase = "READY"  # READY, BOOST, COAST, DESCENT, LANDED
        
    def update(self):
        if self.phase == "LANDED":
            return
            
        # قوة الدفع
        if self.time < self.burn_time:
            thrust = self.thrust
            self.phase = "BOOST"
        else:
            thrust = 0
            if self.vy > 0:
                self.phase = "COAST"
            else:
                self.phase = "DESCENT"
        
        # حساب القوى
        angle_rad = math.radians(self.angle)
        g = 9.81
        
        # السرعة الكلية
        v = math.sqrt(self.vx**2 + self.vy**2)
        
        # السحب
        rho = 1.225 * math.exp(-self.y / 8500)
        drag = 0.5 * rho * v**2 * self.drag_coef * 0.01
        
        if self.phase == "DESCENT":
            drag *= 15  # مظلة
        
        # التسارع
        if self.time < self.burn_time:
            ax = thrust * math.cos(angle_rad) / self.mass
            ay = thrust * math.sin(angle_rad) / self.mass - g
        else:
            if v > 0.1:
                ax = -drag * (self.vx / v) / self.mass
                ay = -drag * (self.vy / v) / self.mass - g
            else:
                ax = 0
                ay = -g
        
        # تحديث السرعة والموقع
        self.vx += ax * self.dt
        self.vy += ay * self.dt
        self.x += self.vx * self.dt
        self.y += self.vy * self.dt
        
        if self.y > self.max_alt:
            self.max_alt = self.y
        
        if self.y <= 0 and self.time > 0.5:
            self.y = 0
            self.phase = "LANDED"
        
        self.trail_x.append(self.x)
        self.trail_y.append(self.y)
        self.time += self.dt

def run_visual_simulation():
    """تشغيل المحاكاة البصرية"""
    
    rocket = VisualRocketSimulator()
    
    # إعداد الرسم
    fig, ax = plt.subplots(figsize=(14, 8))
    fig.patch.set_facecolor('#1a1a2e')
    ax.set_facecolor('#16213e')
    
    # العناصر
    trail_line, = ax.plot([], [], 'orange', linewidth=2, alpha=0.7)
    
    # الصاروخ
    rocket_body = patches.FancyBboxPatch((0, 0), 8, 25, 
                                          boxstyle="round,pad=0.02",
                                          facecolor='#e94560', 
                                          edgecolor='white', linewidth=2)
    ax.add_patch(rocket_body)
    
    # رأس الصاروخ
    rocket_nose = patches.Polygon([[0, 0], [4, 12], [8, 0]], 
                                   facecolor='#0f3460', edgecolor='white')
    ax.add_patch(rocket_nose)
    
    # اللهب
    flame = patches.Polygon([[2, 0], [4, -15], [6, 0]], 
                            facecolor='#ff6b35', edgecolor='yellow', alpha=0.8)
    ax.add_patch(flame)
    
    # النصوص
    title_text = ax.text(0.5, 0.95, '🚀 محاكاة صاروخ علمي', 
                         transform=ax.transAxes, fontsize=18, 
                         color='white', ha='center', fontweight='bold')
    
    info_text = ax.text(0.02, 0.95, '', transform=ax.transAxes, 
                        fontsize=12, color='#00ff88', 
                        verticalalignment='top',
                        fontfamily='monospace',
                        bbox=dict(boxstyle='round', facecolor='#1a1a2e', 
                                  edgecolor='#00ff88', alpha=0.9))
    
    phase_text = ax.text(0.98, 0.95, '', transform=ax.transAxes,
                         fontsize=14, color='yellow', ha='right',
                         fontweight='bold')
    
    # النجوم في الخلفية
    stars_x = np.random.uniform(0, 600, 100)
    stars_y = np.random.uniform(100, 500, 100)
    ax.scatter(stars_x, stars_y, c='white', s=1, alpha=0.5)
    
    # الأرض
    ground = patches.Rectangle((-50, -30), 700, 30, 
                                facecolor='#2d4a22', edgecolor='#1a3015')
    ax.add_patch(ground)
    
    # العشب
    for i in range(0, 600, 10):
        ax.plot([i, i], [0, 5], color='#3d5a32', linewidth=1)
    
    # منصة الإطلاق
    launchpad = patches.Rectangle((40, 0), 20, 5, 
                                    facecolor='#444444', edgecolor='#666666')
    ax.add_patch(launchpad)
    
    ax.set_xlim(-50, 600)
    ax.set_ylim(-30, 500)
    ax.set_aspect('equal')
    ax.axis('off')
    
    def init():
        trail_line.set_data([], [])
        return trail_line, rocket_body, rocket_nose, flame, info_text, phase_text
    
    def animate(frame):
        if rocket.phase != "LANDED":
            rocket.update()
        
        # تحديث المسار
        if len(rocket.trail_x) > 1:
            trail_line.set_data(rocket.trail_x, rocket.trail_y)
        
        # تحديث موقع الصاروخ
        rx = rocket.x - 4
        ry = rocket.y
        
        # تدوير الصاروخ حسب اتجاه الحركة
        if rocket.vy != 0 or rocket.vx != 0:
            angle = math.degrees(math.atan2(rocket.vy, rocket.vx)) - 90
        else:
            angle = rocket.angle - 90
        
        # تحديث جسم الصاروخ
        rocket_body.set_xy((rx, ry))
        
        # تحديث رأس الصاروخ
        nose_points = np.array([[rx, ry + 25], [rx + 4, ry + 37], [rx + 8, ry + 25]])
        rocket_nose.set_xy(nose_points)
        
        # تحديث اللهب
        if rocket.phase == "BOOST":
            flame_size = 15 + np.random.uniform(-3, 3)
            flame_points = np.array([[rx + 2, ry], [rx + 4, ry - flame_size], [rx + 6, ry]])
            flame.set_xy(flame_points)
            flame.set_alpha(0.9)
            flame.set_facecolor(np.random.choice(['#ff6b35', '#ffcc00', '#ff4500']))
        else:
            flame.set_alpha(0)
        
        # تحديث المعلومات
        speed = math.sqrt(rocket.vx**2 + rocket.vy**2)
        info = f"""╔════════════════════════╗
║ الارتفاع: {rocket.y:>7.1f} م   ║
║ السرعة:  {speed:>7.1f} م/ث  ║
║ الزمن:   {rocket.time:>7.1f} ث   ║
║ أقصى:   {rocket.max_alt:>7.1f} م   ║
╚════════════════════════╝"""
        info_text.set_text(info)
        
        # تحديث المرحلة
        phase_colors = {
            "READY": ("#888888", "⚪ جاهز"),
            "BOOST": ("#ff4444", "🔥 الدفع"),
            "COAST": ("#44aaff", "🌙 السفر"),
            "DESCENT": ("#ffaa00", "🪂 الهبوط"),
            "LANDED": ("#44ff44", "✅ هبط!")
        }
        color, text = phase_colors.get(rocket.phase, ("#ffffff", rocket.phase))
        phase_text.set_text(text)
        phase_text.set_color(color)
        
        # تحديث حدود العرض للمتابعة
        if rocket.y > 200:
            ax.set_ylim(rocket.y - 200, rocket.y + 300)
        elif rocket.y < 50:
            ax.set_ylim(-30, 500)
        
        return trail_line, rocket_body, rocket_nose, flame, info_text, phase_text
    
    # تشغيل الأنيميشن
    anim = FuncAnimation(fig, animate, init_func=init, 
                         frames=800, interval=20, blit=False)
    
    plt.tight_layout()
    plt.show()
    
    # طباعة النتائج النهائية
    print("\n" + "="*50)
    print("🚀 انتهت المحاكاة!")
    print("="*50)
    print(f"   أقصى ارتفاع: {rocket.max_alt:.1f} متر")
    print(f"   مكان الهبوط: {rocket.x:.1f} متر")
    print(f"   زمن الرحلة: {rocket.time:.1f} ثانية")
    print("="*50)

if __name__ == "__main__":
    print("="*50)
    print("🚀 جاري تحميل المحاكاة البصرية...")
    print("   انتظر ثوانٍ...")
    print("="*50)
    run_visual_simulation()
