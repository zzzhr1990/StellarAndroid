#pragma once
#include <array>
#include <cmath>
#include <complex>
#include <iomanip>
#include <locale>
#include <sstream>
#include <string>
#include <algorithm>

namespace stellar {
constexpr std::array<double, 10> EqFrequencies{31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
constexpr double EqQ = 1.4142135623730951;
constexpr double Pi = 3.14159265358979323846;

// Same peaking biquad as FFmpeg equalizer (Q width). Estimate the combined peak
// across the audible range, then leave another 0.5 dB instead of summing all boosts.
inline double equalizerHeadroom(const std::array<float, 10>& gains, int rate) {
    std::array<std::array<double, 6>, 10> coeff{};
    bool boost = false;
    for (size_t i=0; i<gains.size(); ++i) {
        if (EqFrequencies[i] >= rate * .5 || gains[i] == 0) continue;
        boost |= gains[i] > 0;
        const double a=std::pow(10., gains[i]/40.), w=2*Pi*EqFrequencies[i]/rate;
        const double alpha=std::sin(w)/(2*EqQ), c=-2*std::cos(w);
        coeff[i]={1+alpha*a,c,1-alpha*a,1+alpha/a,c,1-alpha/a};
    }
    if (!boost) return 0;
    double peak=1;
    auto sample = [&](double f) {
        const auto z=std::polar(1.,-2*Pi*f/rate), z2=z*z;
        double magnitude=1;
        for (const auto& c: coeff) if (c[0]!=0)
            magnitude*=std::abs((c[0]+c[1]*z+c[2]*z2)/(c[3]+c[4]*z+c[5]*z2));
        peak=std::max(peak,magnitude);
    };
    for(int j=0;j<=2048;++j) sample(10*std::pow((rate*.499)/10,j/2048.));
    for(auto f:EqFrequencies) if(f<rate*.5) sample(f);
    return 20*std::log10(peak)+.5;
}

inline std::string equalizerGraph(bool enabled, float preamp, const std::array<float,10>& gains, int rate) {
    if (!enabled) return {};
    std::ostringstream out;
    out.imbue(std::locale::classic());
    out << std::fixed << std::setprecision(6);
    out << "volume=" << preamp-equalizerHeadroom(gains,rate) << "dB:precision=float";
    for(size_t i=0;i<gains.size();++i) {
        if(gains[i]==0 || EqFrequencies[i]>=rate*.5) continue;
        out << ",equalizer=f=" << EqFrequencies[i] << ":t=q:w=" << EqQ << ":g=" << gains[i] << ":r=f32";
    }
    return out.str();
}
}
